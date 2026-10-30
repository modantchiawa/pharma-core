package com.pharmacore.sales.application;

import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receipts of a day (or a few days) by category: cash, each mobile money operator (MyNITA,
 * Airtel Money, Zamani Cash…), card, cheque, third-party payer, credit. The pharmacist sees
 * what each "caisse" took, its total and the transactions behind it, for the whole pharmacy or
 * one checkout station. Days follow the pharmacy's time zone; cancelled sales do not count and
 * payments rejected on the operator's statement are reported apart, outside the totals.
 */
@Service
public class PaymentJournalService {

    /** A period longer than this is a report, not a journal. */
    static final int MAX_DAYS = 31;

    /** One category: {@code provider} is the operator for mobile money and card, null otherwise. */
    public record CategoryTotal(String method, String provider, long count, long totalCents, long pendingCents,
                                long rejectedCount, long rejectedCents) {
    }

    public record DayJournal(LocalDate from, LocalDate to, String terminalId, long count, long totalCents,
                             List<CategoryTotal> categories, List<String> terminals) {
    }

    public record JournalEntry(UUID paymentId, UUID saleId, String ticketNumber, Instant occurredAt,
                               String terminalId, String cashierName, String method, String provider,
                               String reference, long amountCents, String confirmationStatus) {
    }

    private final JdbcClient jdbc;
    private final TenantCalendar calendar;

    public PaymentJournalService(JdbcClient jdbc, TenantCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true)
    public DayJournal journal(LocalDate from, LocalDate to, String terminalId) {
        Period period = period(from, to);
        UUID tenant = TenantContext.requireTenant().value();
        String terminal = blankToNull(terminalId);
        List<CategoryTotal> categories = jdbc.sql("""
                        SELECT p.method, p.provider,
                               count(*) FILTER (WHERE p.confirmation_status <> 'REJECTED') AS count,
                               coalesce(sum(p.amount_cents) FILTER (WHERE p.confirmation_status <> 'REJECTED'), 0) AS total,
                               coalesce(sum(p.amount_cents) FILTER (WHERE p.confirmation_status = 'PENDING'), 0) AS pending,
                               count(*) FILTER (WHERE p.confirmation_status = 'REJECTED') AS rejected_count,
                               coalesce(sum(p.amount_cents) FILTER (WHERE p.confirmation_status = 'REJECTED'), 0) AS rejected
                        FROM sale_payment p
                        JOIN sale s ON s.id = p.sale_id
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :from AND s.occurred_at < :to
                          AND (:terminal::text IS NULL OR s.terminal_id = :terminal::text)
                        GROUP BY p.method, p.provider
                        ORDER BY total DESC, p.method, p.provider""")
                .param("tenant", tenant)
                .param("from", Timestamp.from(period.start()))
                .param("to", Timestamp.from(period.end()))
                .param("terminal", terminal)
                .query((rs, rowNum) -> new CategoryTotal(rs.getString("method"), rs.getString("provider"),
                        rs.getLong("count"), rs.getLong("total"), rs.getLong("pending"),
                        rs.getLong("rejected_count"), rs.getLong("rejected")))
                .list();
        // every station that sold in the period, so the filter offers them even when one is selected
        List<String> terminals = jdbc.sql("""
                        SELECT DISTINCT s.terminal_id FROM sale s
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :from AND s.occurred_at < :to
                        ORDER BY s.terminal_id""")
                .param("tenant", tenant)
                .param("from", Timestamp.from(period.start()))
                .param("to", Timestamp.from(period.end()))
                .query(String.class)
                .list();
        return new DayJournal(period.from(), period.to(), terminal,
                categories.stream().mapToLong(CategoryTotal::count).sum(),
                categories.stream().mapToLong(CategoryTotal::totalCents).sum(), categories, terminals);
    }

    /**
     * The transactions of one category (or all), newest first. {@code provider} narrows mobile
     * money or card to one operator; left out, every operator of the method is listed.
     */
    @Transactional(readOnly = true)
    public List<JournalEntry> entries(LocalDate from, LocalDate to, String method, String provider, String terminalId,
                                      int limit) {
        Period period = period(from, to);
        return jdbc.sql("""
                        SELECT p.id, p.sale_id, s.ticket_number, s.occurred_at, s.terminal_id, u.display_name,
                               p.method, p.provider, p.reference, p.amount_cents, p.confirmation_status
                        FROM sale_payment p
                        JOIN sale s ON s.id = p.sale_id
                        LEFT JOIN app_user u ON u.id = s.cashier_user_id
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :from AND s.occurred_at < :to
                          AND (:method::text IS NULL OR p.method = :method::text)
                          AND (:provider::text IS NULL OR p.provider = :provider::text)
                          AND (:terminal::text IS NULL OR s.terminal_id = :terminal::text)
                        ORDER BY s.occurred_at DESC, s.ticket_number DESC
                        LIMIT :limit""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("from", Timestamp.from(period.start()))
                .param("to", Timestamp.from(period.end()))
                .param("method", blankToNull(method))
                .param("provider", blankToNull(provider))
                .param("terminal", blankToNull(terminalId))
                .param("limit", Math.clamp(limit, 1, 2_000))
                .query((rs, rowNum) -> new JournalEntry(rs.getObject("id", UUID.class),
                        rs.getObject("sale_id", UUID.class), rs.getString("ticket_number"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("terminal_id"),
                        rs.getString("display_name"), rs.getString("method"), rs.getString("provider"),
                        rs.getString("reference"), rs.getLong("amount_cents"), rs.getString("confirmation_status")))
                .list();
    }

    private record Period(LocalDate from, LocalDate to, Instant start, Instant end) {
    }

    /** Local days {@code from}..{@code to} inclusive (today by default) as an instant range. */
    private Period period(LocalDate from, LocalDate to) {
        ZoneId zone = calendar.zone();
        LocalDate first = from != null ? from : to != null ? to : calendar.today();
        LocalDate last = to != null ? to : first;
        if (last.isBefore(first)) {
            throw new BusinessRuleException("invalid-period", "La date de fin précède la date de début");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_DAYS) {
            throw new BusinessRuleException("period-too-long", "Période limitée à " + MAX_DAYS + " jours");
        }
        return new Period(first, last, first.atStartOfDay(zone).toInstant(),
                last.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
