package com.pharmacore.accounting.application;

import com.pharmacore.accounting.domain.Accounts;
import com.pharmacore.accounting.domain.EntryDraft;
import com.pharmacore.accounting.domain.Journal;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Management accounting outputs: journals, ledger, trial balance, income statement and a
 * simplified balance sheet. Statutory statements are produced by the accountant from the CSV
 * export; these reports give the pharmacist the same figures every day.
 */
@Service
public class AccountingReportService {

    private final JdbcClient jdbc;
    private final AccountingPoster poster;
    private final AuditTrail audit;
    private final Clock clock;

    public AccountingReportService(JdbcClient jdbc, AccountingPoster poster, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.poster = poster;
        this.audit = audit;
        this.clock = clock;
    }

    public record JournalLineView(long entryNo, LocalDate date, String journal, String label, String account,
                                  String accountLabel, long debitCents, long creditCents, String referenceType,
                                  UUID referenceId) {
    }

    public record BalanceRow(String account, String label, String kind, long debitCents, long creditCents,
                             long balanceCents) {
    }

    public record IncomeStatement(LocalDate from, LocalDate to, long salesCents, long purchasesCents,
                                  long openingStockCents, long closingStockCents, long costOfGoodsSoldCents,
                                  long grossMarginCents, long otherExpensesCents, long otherIncomeCents,
                                  long resultCents, Map<String, Long> expensesByAccount) {
    }

    public record BalanceSheet(LocalDate asOf, long stockCents, long customerReceivablesCents,
                               long thirdPartyReceivablesCents, long cashCents, long bankCents,
                               long mobileMoneyCents, long chequesCardsCents, long vatDeductibleCents,
                               long totalAssetsCents, long suppliersCents, long vatCollectedCents,
                               long capitalCents, long cumulativeResultCents, long totalLiabilitiesCents) {
    }

    @Transactional(readOnly = true)
    public List<JournalLineView> journal(LocalDate from, LocalDate to, Journal journal, int limit) {
        return jdbc.sql("""
                        SELECT e.entry_no, e.entry_date, e.journal, e.label, l.account_code, a.label AS account_label,
                               l.debit_cents, l.credit_cents, e.reference_type, e.reference_id
                        FROM journal_entry e
                        JOIN journal_line l ON l.entry_id = e.id
                        LEFT JOIN account a ON a.tenant_id = e.tenant_id AND a.code = l.account_code
                        WHERE e.tenant_id = :tenant AND e.entry_date BETWEEN :from AND :to
                          AND (:journal::text IS NULL OR e.journal = :journal::text)
                        ORDER BY e.entry_no, l.line_no
                        LIMIT :limit""")
                .param("tenant", tenant())
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .param("journal", journal == null ? null : journal.name())
                .param("limit", Math.clamp(limit, 1, 20_000))
                .query((rs, rowNum) -> new JournalLineView(rs.getLong("entry_no"), rs.getDate("entry_date").toLocalDate(),
                        rs.getString("journal"), rs.getString("label"), rs.getString("account_code"),
                        rs.getString("account_label"), rs.getLong("debit_cents"), rs.getLong("credit_cents"),
                        rs.getString("reference_type"), rs.getObject("reference_id", UUID.class)))
                .list();
    }

    @Transactional(readOnly = true)
    public List<BalanceRow> trialBalance(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT a.code, a.label, a.kind,
                               coalesce(sum(l.debit_cents), 0) AS debit, coalesce(sum(l.credit_cents), 0) AS credit
                        FROM account a
                        LEFT JOIN journal_line l ON l.tenant_id = a.tenant_id AND l.account_code = a.code
                        LEFT JOIN journal_entry e ON e.id = l.entry_id AND e.entry_date BETWEEN :from AND :to
                        WHERE a.tenant_id = :tenant AND (e.id IS NOT NULL OR l.id IS NULL)
                        GROUP BY a.code, a.label, a.kind
                        HAVING coalesce(sum(l.debit_cents), 0) <> 0 OR coalesce(sum(l.credit_cents), 0) <> 0
                        ORDER BY a.code""")
                .param("tenant", tenant())
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, rowNum) -> new BalanceRow(rs.getString("code"), rs.getString("label"), rs.getString("kind"),
                        rs.getLong("debit"), rs.getLong("credit"), rs.getLong("debit") - rs.getLong("credit")))
                .list();
    }

    /**
     * Result = sales − cost of goods sold − other expenses + other income, where cost of goods
     * sold = purchases + opening stock − closing stock (intermittent inventory, SYSCOHADA).
     */
    @Transactional(readOnly = true)
    public IncomeStatement incomeStatement(LocalDate from, LocalDate to) {
        Map<String, long[]> totals = accountTotals(from, to);
        long sales = sum(totals, code -> code.startsWith("70"), true);
        long otherIncome = sum(totals, code -> Accounts.isRevenue(code) && !code.startsWith("70"), true);
        long purchases = sum(totals, Accounts.PURCHASES::equals, false);
        Map<String, Long> expenses = new LinkedHashMap<>();
        totals.forEach((code, dc) -> {
            if (Accounts.isExpense(code) && !code.equals(Accounts.PURCHASES) && !code.equals(Accounts.STOCK_VARIATION)) {
                expenses.put(code, dc[0] - dc[1]);
            }
        });
        long otherExpenses = expenses.values().stream().mapToLong(Long::longValue).sum();
        long openingStock = stockValuationAt(from.minusDays(1));
        long closingStock = stockValuationAt(to);
        long cogs = purchases + openingStock - closingStock;
        long grossMargin = sales - cogs;
        return new IncomeStatement(from, to, sales, purchases, openingStock, closingStock, cogs, grossMargin,
                otherExpenses, otherIncome, grossMargin - otherExpenses + otherIncome, expenses);
    }

    @Transactional(readOnly = true)
    public BalanceSheet balanceSheet(LocalDate asOf) {
        Map<String, long[]> totals = accountTotals(LocalDate.of(2000, 1, 1), asOf);
        long stock = stockValuationAt(asOf);
        long customers = balance(totals, Accounts.CUSTOMERS);
        long thirdParty = balance(totals, Accounts.THIRD_PARTY_PAYERS);
        long cash = balance(totals, Accounts.CASH);
        long bank = balance(totals, Accounts.BANK);
        long mobile = balance(totals, Accounts.MOBILE_MONEY);
        long cheques = balance(totals, Accounts.CHEQUES_AND_CARDS);
        long vatDeductible = balance(totals, Accounts.VAT_DEDUCTIBLE);
        long suppliers = -balance(totals, Accounts.SUPPLIERS);
        long vatCollected = -balance(totals, Accounts.VAT_COLLECTED);
        long capital = -balance(totals, Accounts.CAPITAL);
        long assets = stock + customers + thirdParty + cash + bank + mobile + cheques + vatDeductible;
        // Cumulative result balances the sheet: everything the activity generated since day one,
        // stock valued at cost rather than through the purchases account.
        long cumulativeResult = assets - suppliers - vatCollected - capital;
        return new BalanceSheet(asOf, stock, customers, thirdParty, cash, bank, mobile, cheques, vatDeductible, assets,
                suppliers, vatCollected, capital, cumulativeResult, suppliers + vatCollected + capital + cumulativeResult);
    }

    /** Closes a month: freezes the stock valuation and refuses further entries dated in it. */
    @Transactional
    public void closePeriod(YearMonth month) {
        UUID tenant = tenant();
        long stock = stockValuationAt(month.atEndOfMonth());
        int updated = jdbc.sql("""
                        INSERT INTO accounting_period (id, tenant_id, year, month, status, closed_at, closed_by, closing_stock_cents)
                        VALUES (:id, :tenant, :year, :month, 'CLOSED', now(), :by, :stock)
                        ON CONFLICT (tenant_id, year, month) DO UPDATE
                        SET status = 'CLOSED', closed_at = now(), closed_by = EXCLUDED.closed_by,
                            closing_stock_cents = EXCLUDED.closing_stock_cents
                        WHERE accounting_period.status = 'OPEN'""")
                .param("id", UUID.randomUUID()).param("tenant", tenant)
                .param("year", month.getYear()).param("month", month.getMonthValue())
                .param("by", TenantContext.requireTenantUser().userId()).param("stock", stock)
                .update();
        if (updated == 0) {
            throw new BusinessRuleException("period-closed", "Période déjà clôturée");
        }
        audit.record("ACCOUNTING_PERIOD_CLOSED", "PERIOD", month.toString(), Map.of("stockCents", Long.toString(stock)));
    }

    /** Manual entry (OD journal) by an authorized user, e.g. owner contribution or bank fees. */
    @Transactional
    public UUID postManual(EntryDraft draft) {
        UUID id = poster.post(draft, "MANUAL", null);
        audit.record("ACCOUNTING_MANUAL_ENTRY", "JOURNAL_ENTRY", id, Map.of(
                "label", draft.label(), "debitCents", Long.toString(draft.totalDebit())));
        return id;
    }

    /** Stock at weighted average cost: lots on hand now, or the frozen value of a closed month. */
    @Transactional(readOnly = true)
    public long stockValuationAt(LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (!date.isBefore(today)) {
            return liveStockValuation();
        }
        YearMonth month = YearMonth.from(date);
        Long frozen = jdbc.sql("""
                        SELECT closing_stock_cents FROM accounting_period
                        WHERE tenant_id = :tenant AND year = :year AND month = :month AND status = 'CLOSED'""")
                .param("tenant", tenant()).param("year", month.getYear()).param("month", month.getMonthValue())
                .query(Long.class).optional().orElse(null);
        if (frozen != null && date.equals(month.atEndOfMonth())) {
            return frozen;
        }
        // Reconstructed from the movement ledger: value on hand now minus what moved since the date.
        return jdbc.sql("""
                        SELECT coalesce(sum(l.quantity * l.unit_cost_cents), 0)
                               - coalesce((SELECT sum(m.quantity * coalesce(ml.unit_cost_cents, 0))
                                           FROM stock_movement m
                                           LEFT JOIN stock_lot ml ON ml.id = m.lot_id
                                           WHERE m.tenant_id = :tenant AND m.occurred_at > :cutoff), 0)
                        FROM stock_lot l WHERE l.tenant_id = :tenant""")
                .param("tenant", tenant())
                .param("cutoff", java.sql.Timestamp.valueOf(date.plusDays(1).atStartOfDay()))
                .query(Long.class).single();
    }

    private long liveStockValuation() {
        return jdbc.sql("SELECT coalesce(sum(quantity * unit_cost_cents), 0) FROM stock_lot WHERE tenant_id = :tenant")
                .param("tenant", tenant()).query(Long.class).single();
    }

    private Map<String, long[]> accountTotals(LocalDate from, LocalDate to) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT l.account_code, sum(l.debit_cents) AS debit, sum(l.credit_cents) AS credit
                        FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id
                        WHERE e.tenant_id = :tenant AND e.entry_date BETWEEN :from AND :to
                        GROUP BY l.account_code ORDER BY l.account_code""")
                .param("tenant", tenant()).param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query(rs -> {
                    totals.put(rs.getString("account_code"), new long[] {rs.getLong("debit"), rs.getLong("credit")});
                });
        return totals;
    }

    private static long balance(Map<String, long[]> totals, String code) {
        long[] dc = totals.getOrDefault(code, new long[] {0, 0});
        return dc[0] - dc[1];
    }

    private static long sum(Map<String, long[]> totals, java.util.function.Predicate<String> accounts, boolean creditSide) {
        return totals.entrySet().stream()
                .filter(e -> accounts.test(e.getKey()))
                .mapToLong(e -> creditSide ? e.getValue()[1] - e.getValue()[0] : e.getValue()[0] - e.getValue()[1])
                .sum();
    }

    private static UUID tenant() {
        return TenantContext.requireTenant().value();
    }
}
