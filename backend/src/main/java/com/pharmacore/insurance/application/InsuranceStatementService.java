package com.pharmacore.insurance.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.insurance.domain.StatementStatus;
import com.pharmacore.insurance.persistence.InsurerEntity;
import com.pharmacore.insurance.persistence.InsurerRepository;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.persistence.TenantSequenceRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statements ("bordereaux") sent to insurers: the covered shares of a period are grouped, sent,
 * then settled, possibly in several payments. Each covered share is billed exactly once.
 */
@Service
public class InsuranceStatementService {

    public static final String STATEMENT_SEQUENCE = "INSURER_STATEMENT";

    /** Insurers settle by bank, cheque, card or mobile money: never through the counter drawer. */
    private static final EnumSet<PaymentMethod> SETTLEMENT_METHODS =
            EnumSet.of(PaymentMethod.CARD, PaymentMethod.CHECK, PaymentMethod.MOBILE_MONEY);

    private final JdbcClient jdbc;
    private final InsurerRepository insurers;
    private final TenantSequenceRepository sequences;
    private final AccountingPoster accounting;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final Clock clock;

    public InsuranceStatementService(JdbcClient jdbc, InsurerRepository insurers, TenantSequenceRepository sequences,
                                     AccountingPoster accounting, TenantCalendar calendar, AuditTrail audit,
                                     Clock clock) {
        this.jdbc = jdbc;
        this.insurers = insurers;
        this.sequences = sequences;
        this.accounting = accounting;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
    }

    public record StatementView(UUID id, UUID insurerId, String insurerName, String number, LocalDate periodFrom,
                                LocalDate periodTo, StatementStatus status, long totalCents, long paidCents,
                                int lineCount, Instant createdAt, Instant issuedAt, Instant paidAt,
                                String paymentMethod, String paymentReference) {

        public long remainingCents() {
            return totalCents - paidCents;
        }
    }

    public record StatementLine(UUID saleId, String ticketNumber, Instant occurredAt, String policyNumber,
                                String beneficiaryName, long saleTotalCents, long amountCents) {
    }

    public record StatementDetail(StatementView statement, List<StatementLine> lines) {
    }

    /**
     * @param billedUnpaidCents issued statements not yet fully settled
     * @param unbilledCents     covered shares of completed sales not yet on a statement
     */
    public record Receivable(UUID insurerId, String insurerName, String accountCode, long billedUnpaidCents,
                             long unbilledCents, int unbilledSales) {

        public long totalCents() {
            return billedUnpaidCents + unbilledCents;
        }
    }

    /** Groups the insurer's unbilled covered shares of the period into a new draft statement. */
    @Transactional
    public StatementView generate(UUID insurerId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("invalid-period", "La fin de période précède son début");
        }
        var insurer = insurers.findById(insurerId).orElseThrow(() -> new NotFoundException("Insurer", insurerId));
        var user = TenantContext.requireTenantUser();
        ZoneId zone = zone();
        Timestamp start = Timestamp.from(from.atStartOfDay(zone).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant());
        List<Object[]> billable = jdbc.sql("""
                        SELECT sp.id, sp.sale_id, sp.amount_cents
                        FROM sale_payment sp
                        JOIN sale s ON s.id = sp.sale_id
                        WHERE sp.tenant_id = :tenant AND sp.insurer_id = :insurer AND sp.statement_id IS NULL
                          AND s.status = 'COMPLETED' AND s.occurred_at >= :from AND s.occurred_at < :to
                        ORDER BY s.occurred_at
                        FOR UPDATE OF sp""")
                .param("tenant", user.tenant().value())
                .param("insurer", insurerId)
                .param("from", start)
                .param("to", end)
                .query((rs, rowNum) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("sale_id", UUID.class),
                        rs.getLong("amount_cents")})
                .list();
        if (billable.isEmpty()) {
            throw new BusinessRuleException("no-billable-payments",
                    "Aucune part tiers payant à facturer à " + insurer.name() + " sur cette période");
        }
        Instant now = clock.instant();
        UUID statementId = UUID.randomUUID();
        String number = "BRD-" + calendar.today().getYear() + "-"
                + String.format("%04d", sequences.next(STATEMENT_SEQUENCE));
        long total = billable.stream().mapToLong(row -> (Long) row[2]).sum();
        jdbc.sql("""
                        INSERT INTO insurer_statement (id, tenant_id, insurer_id, number, period_from, period_to, status,
                                                       total_cents, paid_cents, line_count, created_by, created_at)
                        VALUES (:id, :tenant, :insurer, :number, :from, :to, 'DRAFT', :total, 0, :count, :by, :at)""")
                .param("id", statementId).param("tenant", user.tenant().value()).param("insurer", insurerId)
                .param("number", number).param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .param("total", total).param("count", billable.size()).param("by", user.userId())
                .param("at", Timestamp.from(now)).update();
        for (Object[] row : billable) {
            jdbc.sql("""
                            INSERT INTO insurer_statement_line (id, tenant_id, statement_id, sale_id, sale_payment_id, amount_cents)
                            VALUES (:id, :tenant, :statement, :sale, :payment, :amount)""")
                    .param("id", UUID.randomUUID()).param("tenant", user.tenant().value())
                    .param("statement", statementId).param("sale", row[1]).param("payment", row[0])
                    .param("amount", row[2]).update();
            jdbc.sql("UPDATE sale_payment SET statement_id = :statement WHERE id = :id")
                    .param("statement", statementId).param("id", row[0]).update();
        }
        audit.record("INSURER_STATEMENT_GENERATED", "INSURER_STATEMENT", statementId, Map.of(
                "insurer", insurer.name(), "number", number, "from", from.toString(), "to", to.toString(),
                "lines", Integer.toString(billable.size()), "totalCents", Long.toString(total)));
        return get(statementId).statement();
    }

    @Transactional(readOnly = true)
    public List<StatementView> list(UUID insurerId) {
        return jdbc.sql(VIEW_SQL + " WHERE st.insurer_id = :insurer ORDER BY st.created_at DESC")
                .param("insurer", insurerId)
                .query(this::mapView)
                .list();
    }

    @Transactional(readOnly = true)
    public StatementDetail get(UUID statementId) {
        var statement = jdbc.sql(VIEW_SQL + " WHERE st.id = :id")
                .param("id", statementId)
                .query(this::mapView)
                .optional()
                .orElseThrow(() -> new NotFoundException("Insurer statement", statementId));
        List<StatementLine> lines = jdbc.sql("""
                        SELECT l.sale_id, s.ticket_number, s.occurred_at, p.policy_number, p.beneficiary_name,
                               s.total_net_cents, l.amount_cents
                        FROM insurer_statement_line l
                        JOIN sale s ON s.id = l.sale_id
                        JOIN sale_payment sp ON sp.id = l.sale_payment_id
                        LEFT JOIN insurance_policy p ON p.id = sp.policy_id
                        WHERE l.statement_id = :id
                        ORDER BY s.occurred_at""")
                .param("id", statementId)
                .query((rs, rowNum) -> new StatementLine(rs.getObject("sale_id", UUID.class), rs.getString("ticket_number"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("policy_number"),
                        rs.getString("beneficiary_name"), rs.getLong("total_net_cents"), rs.getLong("amount_cents")))
                .list();
        return new StatementDetail(statement, lines);
    }

    /** Semicolon-separated CSV with a UTF-8 BOM, as spreadsheets in the field expect. */
    @Transactional(readOnly = true)
    public String export(UUID statementId) {
        var detail = get(statementId);
        var csv = new StringBuilder("﻿Bordereau;Assureur;Periode du;au;Ticket;Date;Police;Beneficiaire;Total vente;Part assureur\n");
        for (StatementLine line : detail.lines()) {
            csv.append(detail.statement().number()).append(';')
                    .append(escape(detail.statement().insurerName())).append(';')
                    .append(detail.statement().periodFrom()).append(';').append(detail.statement().periodTo()).append(';')
                    .append(line.ticketNumber()).append(';').append(line.occurredAt()).append(';')
                    .append(escape(line.policyNumber())).append(';').append(escape(line.beneficiaryName())).append(';')
                    .append(line.saleTotalCents()).append(';').append(line.amountCents()).append('\n');
        }
        csv.append("TOTAL;;;;;;;;;").append(detail.statement().totalCents()).append('\n');
        return csv.toString();
    }

    /**
     * Sends the statement: sales cancelled since the draft are dropped (their share becomes
     * billable again elsewhere), totals are recomputed, then the status moves to ISSUED.
     */
    @Transactional
    public StatementView issue(UUID statementId) {
        var current = get(statementId).statement();
        if (current.status() != StatementStatus.DRAFT) {
            throw new BusinessRuleException("statement-not-draft", "Le bordereau " + current.number() + " est déjà émis");
        }
        var user = TenantContext.requireTenantUser();
        jdbc.sql("""
                        UPDATE sale_payment SET statement_id = NULL
                        WHERE id IN (SELECT l.sale_payment_id FROM insurer_statement_line l JOIN sale s ON s.id = l.sale_id
                                     WHERE l.statement_id = :id AND s.status = 'CANCELLED')""")
                .param("id", statementId).update();
        int dropped = jdbc.sql("""
                        DELETE FROM insurer_statement_line l USING sale s
                        WHERE l.statement_id = :id AND s.id = l.sale_id AND s.status = 'CANCELLED'""")
                .param("id", statementId).update();
        int updated = jdbc.sql("""
                        UPDATE insurer_statement st
                        SET status = 'ISSUED', issued_by = :by, issued_at = :at, version = version + 1,
                            total_cents = coalesce((SELECT sum(amount_cents) FROM insurer_statement_line WHERE statement_id = st.id), 0),
                            line_count = (SELECT count(*) FROM insurer_statement_line WHERE statement_id = st.id)
                        WHERE st.id = :id AND st.status = 'DRAFT'""")
                .param("by", user.userId()).param("at", Timestamp.from(clock.instant())).param("id", statementId)
                .update();
        if (updated == 0) {
            throw new BusinessRuleException("statement-not-draft", "Le bordereau a été émis entre-temps");
        }
        var issued = get(statementId).statement();
        if (issued.lineCount() == 0) {
            throw new BusinessRuleException("statement-empty", "Toutes les ventes du bordereau ont été annulées");
        }
        audit.record("INSURER_STATEMENT_ISSUED", "INSURER_STATEMENT", statementId, Map.of(
                "number", issued.number(), "totalCents", Long.toString(issued.totalCents()),
                "droppedCancelled", Integer.toString(dropped)));
        return issued;
    }

    /** Records an insurer settlement; the statement is PAID once the total is covered. */
    @Transactional
    public StatementView recordPayment(UUID statementId, long amountCents, PaymentMethod method, String provider,
                                       String reference) {
        if (!SETTLEMENT_METHODS.contains(method)) {
            throw new BusinessRuleException("invalid-method",
                    "Un assureur règle par virement, chèque, carte ou mobile money");
        }
        var current = get(statementId).statement();
        if (current.status() != StatementStatus.ISSUED) {
            throw new BusinessRuleException("statement-not-issued",
                    "Seul un bordereau émis et non soldé peut être réglé (" + current.status() + ")");
        }
        if (amountCents <= 0 || amountCents > current.remainingCents()) {
            throw new BusinessRuleException("invalid-amount",
                    "Le règlement doit être compris entre 1 et le reste dû (" + current.remainingCents() + ")");
        }
        InsurerEntity insurer = insurers.findById(current.insurerId())
                .orElseThrow(() -> new NotFoundException("Insurer", current.insurerId()));
        boolean settled = amountCents == current.remainingCents();
        Instant now = clock.instant();
        int updated = jdbc.sql("""
                        UPDATE insurer_statement
                        SET paid_cents = paid_cents + :amount, version = version + 1,
                            status = CASE WHEN :settled THEN 'PAID' ELSE status END,
                            paid_at = CASE WHEN :settled THEN :at ELSE paid_at END,
                            payment_method = :method, payment_reference = :reference
                        WHERE id = :id AND status = 'ISSUED' AND paid_cents = :expectedPaid""")
                .param("amount", amountCents).param("settled", settled).param("at", Timestamp.from(now))
                .param("method", method.name()).param("reference", reference).param("id", statementId)
                .param("expectedPaid", current.paidCents())
                .update();
        if (updated == 0) {
            throw new BusinessRuleException("concurrent-settlement", "Le bordereau a été modifié, rechargez-le");
        }
        accounting.post(Postings.insurerPayment(amountCents, method, provider, insurer.name(), insurer.accountCode(),
                current.number(), calendar.today()), "INSURER_STATEMENT_PAYMENT", statementId);
        audit.record("INSURER_STATEMENT_PAID", "INSURER_STATEMENT", statementId, Map.of(
                "number", current.number(), "amountCents", Long.toString(amountCents), "method", method.name(),
                "settled", Boolean.toString(settled)));
        return get(statementId).statement();
    }

    /** Outstanding amounts per insurer, billed or still to bill. */
    @Transactional(readOnly = true)
    public List<Receivable> receivables() {
        return jdbc.sql("""
                        SELECT i.id, i.name, i.account_code,
                               coalesce((SELECT sum(st.total_cents - st.paid_cents) FROM insurer_statement st
                                         WHERE st.insurer_id = i.id AND st.status = 'ISSUED'), 0) AS billed_unpaid,
                               coalesce(u.amount, 0) AS unbilled, coalesce(u.sales, 0) AS unbilled_sales
                        FROM insurer i
                        LEFT JOIN LATERAL (
                            SELECT sum(sp.amount_cents) AS amount, count(DISTINCT sp.sale_id) AS sales
                            FROM sale_payment sp JOIN sale s ON s.id = sp.sale_id
                            WHERE sp.insurer_id = i.id AND sp.statement_id IS NULL AND s.status = 'COMPLETED') u ON true
                        WHERE i.tenant_id = :tenant
                        ORDER BY (coalesce(u.amount, 0) + coalesce((SELECT sum(st.total_cents - st.paid_cents)
                                  FROM insurer_statement st WHERE st.insurer_id = i.id AND st.status = 'ISSUED'), 0)) DESC, i.name""")
                .param("tenant", TenantContext.requireTenant().value())
                .query((rs, rowNum) -> new Receivable(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("account_code"), rs.getLong("billed_unpaid"), rs.getLong("unbilled"),
                        rs.getInt("unbilled_sales")))
                .list();
    }

    private static final String VIEW_SQL = """
            SELECT st.id, st.insurer_id, i.name AS insurer_name, st.number, st.period_from, st.period_to, st.status,
                   st.total_cents, st.paid_cents, st.line_count, st.created_at, st.issued_at, st.paid_at,
                   st.payment_method, st.payment_reference
            FROM insurer_statement st JOIN insurer i ON i.id = st.insurer_id""";

    private StatementView mapView(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StatementView(rs.getObject("id", UUID.class), rs.getObject("insurer_id", UUID.class),
                rs.getString("insurer_name"), rs.getString("number"), rs.getDate("period_from").toLocalDate(),
                rs.getDate("period_to").toLocalDate(), StatementStatus.valueOf(rs.getString("status")),
                rs.getLong("total_cents"), rs.getLong("paid_cents"), rs.getInt("line_count"),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("issued_at")),
                instant(rs.getTimestamp("paid_at")), rs.getString("payment_method"), rs.getString("payment_reference"));
    }

    private ZoneId zone() {
        return jdbc.sql("SELECT time_zone FROM tenant WHERE id = :id")
                .param("id", TenantContext.requireTenant().value())
                .query(String.class).optional().map(ZoneId::of).orElse(ZoneId.of("UTC"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(";") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
