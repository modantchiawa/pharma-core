package com.pharmacore.sales.application;

import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The external balance of the pharmacy's mobile money wallets (Airtel, Moov, Orange…): the
 * money held at the operator, outside the drawer. The balance is read from the operator's SMS
 * ("Nouveau solde : …") forwarded by the phone, or typed by the pharmacist from the operator's
 * app. Since that reading, the receipts booked at the counter tell what the balance should be
 * now; a gap means a payment the operator did not credit, or a withdrawal not recorded.
 */
@Service
public class MobileMoneyWalletService {

    /**
     * @param lastBalanceCents last balance read (null when never read)
     * @param receivedSinceCents mobile money receipts booked since that reading (sales and debts)
     * @param expectedBalanceCents last balance plus the receipts since, null when never read
     */
    public record Wallet(String provider, Long lastBalanceCents, Instant observedAt, String source,
                         long receivedSinceCents, int paymentsSince, Long expectedBalanceCents,
                         long receivedTodayCents) {
    }

    public record BalanceReading(UUID id, String provider, long balanceCents, String source, String note,
                                 String recordedBy, Instant observedAt) {
    }

    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final TenantCalendar calendar;
    private final Clock clock;

    public MobileMoneyWalletService(JdbcClient jdbc, AuditTrail audit, TenantCalendar calendar, Clock clock) {
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** A balance announced by an operator SMS, stored with the confirmation it came with. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFromSms(String provider, long balanceCents, UUID notificationId, Instant observedAt) {
        insert(provider, balanceCents, "SMS", notificationId, null, null, observedAt);
    }

    /** A balance read by the pharmacist on the operator's app or USSD menu. */
    @Transactional
    public BalanceReading recordManual(String provider, long balanceCents, String note) {
        if (provider == null || provider.isBlank()) {
            throw new BusinessRuleException("provider-required", "Choisissez l'opérateur du portefeuille");
        }
        if (balanceCents < 0) {
            throw new BusinessRuleException("negative-balance", "Le solde ne peut pas être négatif");
        }
        var user = TenantContext.requireTenantUser();
        UUID id = insert(provider.trim().toUpperCase(), balanceCents, "MANUAL", null, note, user.userId(),
                clock.instant());
        audit.record("MOBILE_MONEY_BALANCE_RECORDED", "MOBILE_MONEY_BALANCE", id, Map.of(
                "provider", provider, "balanceCents", Long.toString(balanceCents)));
        return history(provider, 1).getFirst();
    }

    /** Every operator the pharmacy uses: read balances and receipts of the last 90 days. */
    @Transactional(readOnly = true)
    public List<Wallet> wallets() {
        UUID tenant = TenantContext.requireTenant().value();
        Instant now = clock.instant();
        var providers = new TreeSet<String>(jdbc.sql("""
                        SELECT DISTINCT provider FROM mobile_money_balance WHERE tenant_id = :tenant
                        UNION
                        SELECT DISTINCT p.provider FROM sale_payment p JOIN sale s ON s.id = p.sale_id
                        WHERE s.tenant_id = :tenant AND p.method = 'MOBILE_MONEY' AND p.provider IS NOT NULL
                          AND s.occurred_at >= :since""")
                .param("tenant", tenant)
                .param("since", Timestamp.from(now.minus(90, ChronoUnit.DAYS)))
                .query(String.class)
                .list());
        Instant startOfDay = calendar.today().atStartOfDay(calendar.zone()).toInstant();
        List<Wallet> wallets = new ArrayList<>();
        for (String provider : providers) {
            var last = history(provider, 1).stream().findFirst();
            Instant since = last.map(BalanceReading::observedAt).orElse(startOfDay);
            long[] received = receivedSince(tenant, provider, since);
            long today = receivedSince(tenant, provider, startOfDay)[0];
            wallets.add(new Wallet(provider, last.map(BalanceReading::balanceCents).orElse(null),
                    last.map(BalanceReading::observedAt).orElse(null), last.map(BalanceReading::source).orElse(null),
                    received[0], (int) received[1],
                    last.map(reading -> reading.balanceCents() + received[0]).orElse(null), today));
        }
        return wallets;
    }

    @Transactional(readOnly = true)
    public List<BalanceReading> history(String provider, int limit) {
        return jdbc.sql("""
                        SELECT b.id, b.provider, b.balance_cents, b.source, b.note, u.display_name, b.observed_at
                        FROM mobile_money_balance b LEFT JOIN app_user u ON u.id = b.recorded_by
                        WHERE b.tenant_id = :tenant AND (:provider::text IS NULL OR b.provider = :provider::text)
                        ORDER BY b.observed_at DESC
                        LIMIT :limit""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("provider", provider == null || provider.isBlank() ? null : provider.trim().toUpperCase())
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, rowNum) -> new BalanceReading(rs.getObject("id", UUID.class), rs.getString("provider"),
                        rs.getLong("balance_cents"), rs.getString("source"), rs.getString("note"),
                        rs.getString("display_name"), rs.getTimestamp("observed_at").toInstant()))
                .list();
    }

    /** Mobile money booked for the operator after {@code since}: sales not rejected, plus debts collected. */
    private long[] receivedSince(UUID tenant, String provider, Instant since) {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount), 0) AS total, count(*) AS payments FROM (
                            SELECT p.amount_cents AS amount
                            FROM sale_payment p JOIN sale s ON s.id = p.sale_id
                            WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED' AND p.method = 'MOBILE_MONEY'
                              AND p.provider = :provider AND p.confirmation_status <> 'REJECTED'
                              AND s.occurred_at > :since
                            UNION ALL
                            SELECT c.amount_cents
                            FROM customer_payment c
                            WHERE c.tenant_id = :tenant AND c.method = 'MOBILE_MONEY' AND c.provider = :provider
                              AND c.received_at > :since
                        ) receipts""")
                .param("tenant", tenant)
                .param("provider", provider)
                .param("since", Timestamp.from(since))
                .query((rs, rowNum) -> new long[] {rs.getLong("total"), rs.getLong("payments")})
                .single();
    }

    private UUID insert(String provider, long balanceCents, String source, UUID notificationId, String note,
                        UUID recordedBy, Instant observedAt) {
        UUID id = UUID.randomUUID();
        UUID by = recordedBy != null ? recordedBy
                : TenantContext.actor() instanceof Actor.TenantUser user ? user.userId() : null;
        jdbc.sql("""
                        INSERT INTO mobile_money_balance (id, tenant_id, provider, balance_cents, source, notification_id,
                                                          note, recorded_by, observed_at, created_at)
                        VALUES (:id, :tenant, :provider, :balance, :source, :notification, :note, :by, :observed, :now)""")
                .param("id", id)
                .param("tenant", TenantContext.requireTenant().value())
                .param("provider", provider)
                .param("balance", balanceCents)
                .param("source", source)
                .param("notification", notificationId)
                .param("note", note == null || note.isBlank() ? null : note.trim())
                .param("by", by)
                .param("observed", Timestamp.from(observedAt))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return id;
    }
}
