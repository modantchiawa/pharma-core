package com.pharmacore.followup.application;

import com.pharmacore.followup.domain.MessageKind;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.prescription.application.PrescriptionService;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds, once a day, what the pharmacy should tell its patients: a treatment that ends the day
 * after tomorrow (from the posology typed at the counter), a prescription that can be renewed,
 * a credit account left unpaid. Idempotent: every cause has one dedupe key.
 */
@Service
public class FollowUpGenerator {

    /** The end-of-treatment message leaves this many days before the last day. */
    static final int TREATMENT_END_NOTICE_DAYS = 2;
    /** Treatments shorter than this are not worth a message. */
    static final int MIN_TREATMENT_DAYS = 5;

    public record Report(int treatmentEnds, int renewals, int creditReminders) {

        public int total() {
            return treatmentEnds + renewals + creditReminders;
        }
    }

    private final CustomerMessageService messages;
    private final FollowUpSettingsService settings;
    private final PrescriptionService prescriptions;
    private final TenantRepository tenants;
    private final TenantCalendar calendar;
    private final JdbcClient jdbc;

    public FollowUpGenerator(CustomerMessageService messages, FollowUpSettingsService settings,
                             PrescriptionService prescriptions, TenantRepository tenants, TenantCalendar calendar,
                             JdbcClient jdbc) {
        this.messages = messages;
        this.settings = settings;
        this.prescriptions = prescriptions;
        this.tenants = tenants;
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    @Transactional
    public Report generate() {
        var values = settings.effective().values();
        if (!values.enabled()) {
            return new Report(0, 0, 0);
        }
        LocalDate today = calendar.today();
        int ends = values.treatmentEndEnabled() ? treatmentEnds(today) : 0;
        int renewals = values.renewalEnabled() ? renewals(values.renewalDaysAhead(), today) : 0;
        int credits = values.creditReminderEnabled()
                ? creditReminders(values.creditReminderAfterDays(), values.creditReminderEveryDays(), today) : 0;
        return new Report(ends, renewals, credits);
    }

    private record TreatmentEnd(UUID lineId, UUID customerId, String designation, LocalDate endsOn) {
    }

    private int treatmentEnds(LocalDate today) {
        UUID tenant = TenantContext.requireTenant().value();
        ZoneId zone = tenants.findById(tenant).map(t -> ZoneId.of(t.timeZone())).orElse(ZoneId.of("UTC"));
        List<TreatmentEnd> ends = jdbc.sql("""
                        SELECT l.id, s.customer_id, p.designation,
                               (s.occurred_at AT TIME ZONE :zone)::date + ((l.posology->>'durationDays')::int - 1) AS ends_on
                        FROM sale_line l
                        JOIN sale s ON s.id = l.sale_id
                        JOIN product p ON p.id = l.product_id
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED' AND s.customer_id IS NOT NULL
                          AND l.posology IS NOT NULL AND (l.posology->>'durationDays')::int >= :minDays
                          AND s.occurred_at >= :since
                          AND (s.occurred_at AT TIME ZONE :zone)::date + ((l.posology->>'durationDays')::int - 1) = :target""")
                .param("tenant", tenant)
                .param("zone", zone.getId())
                .param("minDays", MIN_TREATMENT_DAYS)
                .param("since", Timestamp.from(today.minusDays(400).atStartOfDay(zone).toInstant()))
                .param("target", Date.valueOf(today.plusDays(TREATMENT_END_NOTICE_DAYS)))
                .query((rs, rowNum) -> new TreatmentEnd(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getString("designation"),
                        rs.getDate("ends_on").toLocalDate()))
                .list();
        int queued = 0;
        for (TreatmentEnd end : ends) {
            if (messages.queue(new CustomerMessageService.Draft(end.customerId(), MessageKind.TREATMENT_END,
                    "TREATMENT_END:" + end.lineId(), "SALE_LINE", end.lineId(),
                    Map.of("produit", end.designation(), "date", CustomerMessageService.day(end.endsOn()))))
                    .isPresent()) {
                queued++;
            }
        }
        return queued;
    }

    private int renewals(int daysAhead, LocalDate today) {
        int queued = 0;
        for (var prescription : prescriptions.renewalsDue(daysAhead)) {
            if (prescription.customerId() == null || prescription.nextRenewalDue() == null) {
                continue;
            }
            LocalDate due = prescription.nextRenewalDue();
            if (due.isAfter(today.plusDays(daysAhead))) {
                continue;
            }
            if (messages.queue(new CustomerMessageService.Draft(prescription.customerId(), MessageKind.RENEWAL_DUE,
                    "RENEWAL:" + prescription.id() + ":" + due, "PRESCRIPTION", prescription.id(),
                    Map.of("date", CustomerMessageService.day(due.isBefore(today) ? today : due)))).isPresent()) {
                queued++;
            }
        }
        return queued;
    }

    private record Debtor(UUID customerId, long balanceCents) {
    }

    private int creditReminders(int afterDays, int everyDays, LocalDate today) {
        UUID tenant = TenantContext.requireTenant().value();
        List<Debtor> debtors = jdbc.sql("""
                        SELECT c.id, c.credit_balance_cents
                        FROM customer c
                        WHERE c.tenant_id = :tenant AND c.active AND c.messaging_consent AND c.credit_balance_cents > 0
                          AND coalesce((SELECT max(p.received_at) FROM customer_payment p WHERE p.customer_id = c.id),
                                       '1970-01-01'::timestamptz) < now() - make_interval(days => :afterDays)
                          AND coalesce((SELECT max(s.occurred_at) FROM sale s JOIN sale_payment sp ON sp.sale_id = s.id
                                        WHERE s.customer_id = c.id AND sp.method = 'CREDIT'),
                                       '1970-01-01'::timestamptz) < now() - make_interval(days => :afterDays)
                          AND NOT EXISTS (SELECT 1 FROM customer_message m
                                          WHERE m.customer_id = c.id AND m.kind = 'CREDIT_REMINDER'
                                            AND m.status <> 'CANCELLED'
                                            AND m.created_at > now() - make_interval(days => :everyDays))
                        ORDER BY c.credit_balance_cents DESC
                        LIMIT 200""")
                .param("tenant", tenant)
                .param("afterDays", afterDays)
                .param("everyDays", everyDays)
                .query((rs, rowNum) -> new Debtor(rs.getObject("id", UUID.class), rs.getLong("credit_balance_cents")))
                .list();
        int queued = 0;
        for (Debtor debtor : debtors) {
            if (messages.queue(new CustomerMessageService.Draft(debtor.customerId(), MessageKind.CREDIT_REMINDER,
                    "CREDIT:" + debtor.customerId() + ":" + today, "CUSTOMER", debtor.customerId(),
                    Map.of("montant", messages.money(debtor.balanceCents())))).isPresent()) {
                queued++;
            }
        }
        return queued;
    }
}
