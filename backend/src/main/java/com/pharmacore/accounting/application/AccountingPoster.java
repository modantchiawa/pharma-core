package com.pharmacore.accounting.application;

import com.pharmacore.accounting.domain.EntryDraft;
import com.pharmacore.sales.persistence.TenantSequenceRepository;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes journal entries in the same transaction as the business operation they describe.
 * Entries are immutable and numbered without gaps; a closed period refuses new entries.
 */
@Service
public class AccountingPoster {

    public static final String JOURNAL_SEQUENCE = "JOURNAL";

    private final JdbcClient jdbc;
    private final TenantSequenceRepository sequences;
    private final Clock clock;

    public AccountingPoster(JdbcClient jdbc, TenantSequenceRepository sequences, Clock clock) {
        this.jdbc = jdbc;
        this.sequences = sequences;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(EntryDraft draft, String referenceType, UUID referenceId) {
        return post(draft, referenceType, referenceId, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(EntryDraft draft, String referenceType, UUID referenceId, UUID reversalOf) {
        UUID tenant = TenantContext.requireTenant().value();
        ensureOpen(tenant, draft.date());
        for (EntryDraft.Line line : draft.lines()) {
            ensureAccountExists(tenant, line.account());
        }
        UUID entryId = UUID.randomUUID();
        long number = sequences.next(JOURNAL_SEQUENCE);
        jdbc.sql("""
                        INSERT INTO journal_entry (id, tenant_id, journal, entry_no, entry_date, label, reference_type,
                                                   reference_id, reversal_of, posted_by, posted_at)
                        VALUES (:id, :tenant, :journal, :no, :date, :label, :refType, :refId, :reversal, :by, :at)""")
                .param("id", entryId)
                .param("tenant", tenant)
                .param("journal", draft.journal().name())
                .param("no", number)
                .param("date", Date.valueOf(draft.date()))
                .param("label", truncate(draft.label(), 255))
                .param("refType", referenceType)
                .param("refId", referenceId)
                .param("reversal", reversalOf)
                .param("by", TenantContext.actor().auditType() + ":" + TenantContext.actor().auditId())
                .param("at", Timestamp.from(clock.instant()))
                .update();
        short lineNo = 1;
        for (EntryDraft.Line line : draft.lines()) {
            jdbc.sql("""
                            INSERT INTO journal_line (id, tenant_id, entry_id, line_no, account_code, debit_cents, credit_cents, label)
                            VALUES (:id, :tenant, :entry, :no, :account, :debit, :credit, :label)""")
                    .param("id", UUID.randomUUID())
                    .param("tenant", tenant)
                    .param("entry", entryId)
                    .param("no", lineNo++)
                    .param("account", line.account())
                    .param("debit", line.debitCents())
                    .param("credit", line.creditCents())
                    .param("label", truncate(line.label(), 255))
                    .update();
        }
        return entryId;
    }

    /** Reverses every entry recorded for a business reference (e.g. a cancelled sale). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> reverse(String referenceType, UUID referenceId, LocalDate on, String label) {
        UUID tenant = TenantContext.requireTenant().value();
        List<UUID> originals = jdbc.sql("""
                        SELECT e.id FROM journal_entry e
                        WHERE e.tenant_id = :tenant AND e.reference_type = :type AND e.reference_id = :ref
                          AND e.reversal_of IS NULL
                          AND NOT EXISTS (SELECT 1 FROM journal_entry r WHERE r.reversal_of = e.id)""")
                .param("tenant", tenant).param("type", referenceType).param("ref", referenceId)
                .query(UUID.class).list();
        return originals.stream().map(original -> {
            var draft = load(original);
            return post(draft.reversed(on, label), referenceType, referenceId, original);
        }).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EntryDraft load(UUID entryId) {
        var header = jdbc.sql("SELECT journal, entry_date, label FROM journal_entry WHERE id = :id")
                .param("id", entryId)
                .query((rs, rowNum) -> new Object[] {rs.getString("journal"), rs.getDate("entry_date").toLocalDate(), rs.getString("label")})
                .single();
        List<EntryDraft.Line> lines = jdbc.sql("""
                        SELECT account_code, debit_cents, credit_cents, label FROM journal_line
                        WHERE entry_id = :id ORDER BY line_no""")
                .param("id", entryId)
                .query((rs, rowNum) -> new EntryDraft.Line(rs.getString("account_code"), rs.getLong("debit_cents"),
                        rs.getLong("credit_cents"), rs.getString("label")))
                .list();
        return new EntryDraft(com.pharmacore.accounting.domain.Journal.valueOf((String) header[0]),
                (LocalDate) header[1], (String) header[2], lines);
    }

    private void ensureOpen(UUID tenant, LocalDate date) {
        Optional<String> status = jdbc.sql("""
                        SELECT status FROM accounting_period
                        WHERE tenant_id = :tenant AND year = :year AND month = :month""")
                .param("tenant", tenant).param("year", date.getYear()).param("month", date.getMonthValue())
                .query(String.class).optional();
        if (status.filter("CLOSED"::equals).isPresent()) {
            throw new BusinessRuleException("period-closed",
                    "La période " + date.getMonthValue() + "/" + date.getYear() + " est clôturée");
        }
    }

    private void ensureAccountExists(UUID tenant, String code) {
        Integer found = jdbc.sql("SELECT 1 FROM account WHERE tenant_id = :tenant AND code = :code AND active")
                .param("tenant", tenant).param("code", code).query(Integer.class).optional().orElse(null);
        if (found == null) {
            throw new BusinessRuleException("unknown-account", "Compte " + code + " inconnu dans le plan comptable");
        }
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
