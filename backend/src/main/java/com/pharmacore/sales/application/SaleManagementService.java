package com.pharmacore.sales.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.control.application.FourEyesService;
import com.pharmacore.control.domain.FourEyesPolicy;
import com.pharmacore.customers.application.CustomerService;
import com.pharmacore.inventory.application.StockLedger;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.inventory.domain.LotAllocation;
import com.pharmacore.inventory.persistence.StockMovementEntity;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleLineEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleManagementService {

    private final SaleRepository sales;
    private final StockLedger ledger;
    private final AuditTrail audit;
    private final Clock clock;

    private final CustomerService customers;
    private final CashSessionService cashSessions;
    private final AccountingPoster accounting;
    private final TenantCalendar calendar;
    private final CashPolicyService policy;
    private final SupervisorApprovalService approvals;
    private final FourEyesService fourEyes;
    private final JdbcClient jdbc;

    public SaleManagementService(SaleRepository sales, StockLedger ledger, AuditTrail audit, Clock clock,
                                 CustomerService customers, CashSessionService cashSessions,
                                 AccountingPoster accounting, TenantCalendar calendar, CashPolicyService policy,
                                 SupervisorApprovalService approvals, FourEyesService fourEyes, JdbcClient jdbc) {
        this.sales = sales;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
        this.customers = customers;
        this.cashSessions = cashSessions;
        this.accounting = accounting;
        this.calendar = calendar;
        this.policy = policy;
        this.approvals = approvals;
        this.fourEyes = fourEyes;
        this.jdbc = jdbc;
    }

    /** One line of a partial return: the sale line number and how many units come back. */
    public record ReturnLine(short lineNo, int quantity) {
    }

    /** A recorded return; {@code approvedBy} is the second person when one signed. */
    public record ReturnResult(UUID returnId, UUID saleId, long refundCents, int units, UUID approvedBy) {
    }

    @Transactional(readOnly = true)
    public SaleEntity get(UUID saleId) {
        var sale = sales.findWithLines(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        sale.payments().size();
        return sale;
    }

    @Transactional(readOnly = true)
    public List<SaleEntity> period(Instant from, Instant to, int limit) {
        return sales.findInPeriod(from, to, PageRequest.of(0, Math.clamp(limit, 1, 500)));
    }

    @Transactional(readOnly = true)
    public List<SaleEntity> reviewQueue(int limit) {
        return sales.findReviewQueue(PageRequest.of(0, Math.clamp(limit, 1, 200)));
    }

    /** Units already returned per sale line, so that nothing comes back twice. */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> returnedQuantities(UUID saleId) {
        Map<UUID, Integer> returned = new HashMap<>();
        jdbc.sql("""
                        SELECT rl.sale_line_id, sum(rl.quantity) AS quantity
                        FROM sale_return_line rl JOIN sale_return r ON r.id = rl.sale_return_id
                        WHERE r.sale_id = :sale
                        GROUP BY rl.sale_line_id""")
                .param("sale", saleId)
                .query((RowCallbackHandler) rs -> returned.put(rs.getObject("sale_line_id", UUID.class),
                        rs.getInt("quantity")));
        return returned;
    }

    /**
     * Cancels a sale: units go back into the exact lots they came from, the customer's credit is
     * released, the accounting entry is reversed and cash refunded leaves the current drawer.
     * A cancellation above the pharmacy's threshold, or of a sale whose drawer is already closed,
     * must be signed by a holder of {@code CASH_APPROVE} (the actor, or a supervisor's PIN).
     * Independently, a paid sale above the four-eyes threshold, or one with a narcotic, needs a
     * second person who is not the requester; their signature also covers the cash rule when
     * they hold {@code CASH_APPROVE}.
     */
    @Transactional
    public SaleEntity cancel(UUID saleId, String reason, String terminalId,
                             SupervisorApprovalService.SupervisorApproval approval) {
        var sale = sales.findWithLines(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        if (sale.status() == SaleEntity.Status.CANCELLED) {
            throw new BusinessRuleException("already-cancelled", "Sale " + sale.ticketNumber() + " is already cancelled");
        }
        var actor = TenantContext.requireTenantUser();
        UUID userId = actor.userId();
        Instant now = clock.instant();
        long threshold = policy.current().cancelApprovalThresholdCents();
        boolean aboveThreshold = threshold > 0 && sale.totalNetCents() > threshold;
        boolean drawerClosed = sale.cashSessionId() != null && cashSessions.isClosed(sale.cashSessionId());
        long paid = paidCents(sale);
        boolean narcotic = containsNarcotic(sale.lines().stream().map(SaleLineEntity::productId).toList());
        var secondPerson = fourEyes.check(new FourEyesService.Request(FourEyesPolicy.Action.SALE_CANCELLATION,
                "SALE", saleId, paid, narcotic, reason, approval == null ? null
                : new FourEyesService.Credentials(approval.email(), approval.pin())));
        UUID approvedBy = null;
        if (secondPerson.isPresent()) {
            var approver = secondPerson.get().approver();
            if ((aboveThreshold || drawerClosed) && !actor.can(SupervisorApprovalService.PERMISSION)
                    && !approver.can(SupervisorApprovalService.PERMISSION)) {
                throw new BusinessRuleException("approval-not-authorized",
                        approver.displayName() + " n'est pas habilité à approuver cette opération");
            }
            approvedBy = approver.userId();
            sale.approve(approvedBy, now);
        } else if (aboveThreshold || drawerClosed) {
            approvedBy = approvals.require(approval, aboveThreshold ? "SALE_CANCELLATION_ABOVE_THRESHOLD"
                    : "SALE_CANCELLATION_CLOSED_DRAWER", "SALE", saleId);
            sale.approve(approvedBy, now);
        }
        var reference = new StockLedger.Reference("SALE_CANCELLATION", saleId, now, userId);
        for (SaleLineEntity line : sale.lines()) {
            ledger.restore(line.productId(), line.lotAllocations(), line.uncoveredQuantity(), reference);
        }
        sale.cancel(userId, reason, now);
        if (sale.customerId() != null && sale.creditCents() > 0) {
            customers.releaseCredit(sale.customerId(), sale.creditCents());
        }
        accounting.reverse("SALE", saleId, calendar.today(), "Annulation vente " + sale.ticketNumber());
        long cashRefund = sale.payments().stream()
                .filter(p -> p.method() == PaymentMethod.CASH)
                .mapToLong(SalePaymentEntity::amountCents).sum();
        if (cashRefund > 0 && terminalId != null) {
            cashSessions.sessionFor(terminalId, now).ifPresent(session -> cashSessions.recordRefund(session,
                    cashRefund, "Remboursement vente " + sale.ticketNumber()));
        }
        audit.record("SALE_CANCELLED", "SALE", saleId, Map.of(
                "ticket", sale.ticketNumber(),
                "netCents", Long.toString(sale.totalNetCents()),
                "paidCents", Long.toString(paid),
                "narcotic", Boolean.toString(narcotic),
                "cashierId", sale.cashierUserId().toString(),
                "occurredAt", sale.occurredAt().toString(),
                "reason", reason,
                "approvedBy", String.valueOf(approvedBy)));
        return sale;
    }

    /**
     * Partial return of a paid sale: the units go back into the lots they came from and the
     * customer is refunded in cash from the drawer of this terminal. The refund is the line's net
     * amount (after discount) pro rata. A refund above the four-eyes threshold, or of a narcotic,
     * is signed by a second person. Sales on credit or with a third-party payer are cancelled,
     * not returned, so that the receivables stay right.
     */
    @Transactional
    public ReturnResult returnLines(UUID saleId, List<ReturnLine> requested, String reason, String terminalId,
                                    FourEyesService.Credentials approver) {
        var sale = sales.findWithLines(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        if (sale.status() == SaleEntity.Status.CANCELLED) {
            throw new BusinessRuleException("sale-cancelled", "Le ticket " + sale.ticketNumber() + " est annulé");
        }
        if (sale.payments().stream().anyMatch(p -> p.method() == PaymentMethod.CREDIT
                || p.method() == PaymentMethod.THIRD_PARTY_PAYER)) {
            throw new BusinessRuleException("return-not-cash",
                    "Ce ticket a été réglé à crédit ou en tiers payant : annulez-le plutôt que de le retourner");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("reason-required", "Le motif est obligatoire");
        }
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        UUID session = terminalId == null ? null : cashSessions.sessionFor(terminalId, now).orElse(null);
        if (session == null) {
            throw new BusinessRuleException("drawer-closed",
                    "Aucune caisse ouverte sur ce poste : ouvrez la session pour rembourser le retour");
        }
        Map<Short, SaleLineEntity> byNumber = sale.lines().stream()
                .collect(Collectors.toMap(SaleLineEntity::lineNo, l -> l));
        Map<UUID, Integer> alreadyReturned = returnedQuantities(saleId);
        Map<SaleLineEntity, Integer> quantities = new LinkedHashMap<>();
        for (ReturnLine line : requested) {
            SaleLineEntity saleLine = byNumber.get(line.lineNo());
            if (saleLine == null || line.quantity() <= 0) {
                throw new BusinessRuleException("return-line", "Ligne " + line.lineNo() + " inconnue sur ce ticket");
            }
            int total = quantities.merge(saleLine, line.quantity(), Integer::sum);
            int remaining = saleLine.quantity() - alreadyReturned.getOrDefault(saleLine.id(), 0);
            if (total > remaining) {
                throw new BusinessRuleException("return-quantity",
                        "Ligne " + line.lineNo() + " : " + remaining + " unité(s) peuvent encore être retournées");
            }
        }
        if (quantities.isEmpty()) {
            throw new BusinessRuleException("return-line", "Indiquez au moins une ligne à retourner");
        }
        Map<SaleLineEntity, Long> refunds = new LinkedHashMap<>();
        quantities.forEach((line, quantity) -> refunds.put(line, BigDecimal.valueOf(line.netCents())
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(line.quantity()), 0, RoundingMode.HALF_UP)
                .longValueExact()));
        long refund = refunds.values().stream().mapToLong(Long::longValue).sum();
        if (refund <= 0) {
            throw new BusinessRuleException("return-amount", "Rien à rembourser sur ces lignes");
        }
        UUID returnId = UUID.randomUUID();
        boolean narcotic = containsNarcotic(quantities.keySet().stream().map(SaleLineEntity::productId).toList());
        var approval = fourEyes.check(new FourEyesService.Request(FourEyesPolicy.Action.SALE_RETURN, "SALE",
                saleId, refund, narcotic, reason, approver));

        jdbc.sql("""
                        INSERT INTO sale_return (id, tenant_id, sale_id, refund_cents, reason, terminal_id, cash_session_id,
                                                 recorded_by, approval_id, occurred_at)
                        VALUES (:id, :tenant, :sale, :refund, :reason, :terminal, :session, :by, :approval, :at)""")
                .param("id", returnId)
                .param("tenant", user.tenant().value())
                .param("sale", saleId)
                .param("refund", refund)
                .param("reason", reason.trim())
                .param("terminal", terminalId)
                .param("session", session)
                .param("by", user.userId())
                .param("approval", approval.map(FourEyesService.Approval::id).orElse(null))
                .param("at", Timestamp.from(now))
                .update();
        Map<UUID, Integer> backInLots = returnedByLot(saleId);
        var reference = new StockLedger.Reference("SALE_RETURN", returnId, now, user.userId());
        List<Postings.ReturnedLine> posted = new ArrayList<>();
        int units = 0;
        for (var entry : quantities.entrySet()) {
            SaleLineEntity line = entry.getKey();
            int quantity = entry.getValue();
            units += quantity;
            jdbc.sql("""
                            INSERT INTO sale_return_line (id, tenant_id, sale_return_id, sale_line_id, product_id, quantity,
                                                          refund_cents)
                            VALUES (:id, :tenant, :return, :line, :product, :quantity, :refund)""")
                    .param("id", UUID.randomUUID())
                    .param("tenant", user.tenant().value())
                    .param("return", returnId)
                    .param("line", line.id())
                    .param("product", line.productId())
                    .param("quantity", quantity)
                    .param("refund", refunds.get(line))
                    .update();
            // Back into the lots the units came from, as far as they were not already returned
            List<LotAllocation> back = new ArrayList<>();
            int left = quantity;
            for (LotAllocation allocation : line.lotAllocations()) {
                int room = allocation.quantity() - backInLots.getOrDefault(allocation.lotId(), 0);
                int put = Math.min(Math.max(room, 0), left);
                if (put > 0) {
                    back.add(new LotAllocation(allocation.lotId(), put));
                    backInLots.merge(allocation.lotId(), put, Integer::sum);
                    left -= put;
                }
            }
            ledger.restore(line.productId(), back, left, reference, StockMovementEntity.Type.SALE_RETURN);
            posted.add(new Postings.ReturnedLine(refunds.get(line), line.vatRate()));
        }
        cashSessions.recordRefund(session, refund, "Retour ticket " + sale.ticketNumber());
        accounting.post(Postings.saleReturn(sale.ticketNumber(), posted, calendar.today()), "SALE_RETURN", returnId);
        audit.record("SALE_RETURNED", "SALE", saleId, Map.of(
                "returnId", returnId.toString(),
                "ticket", sale.ticketNumber(),
                "refundCents", Long.toString(refund),
                "units", Integer.toString(units),
                "narcotic", Boolean.toString(narcotic),
                "reason", reason.trim(),
                "approvedBy", approval.map(a -> a.approverId().toString()).orElse("")));
        return new ReturnResult(returnId, saleId, refund, units,
                approval.map(FourEyesService.Approval::approverId).orElse(null));
    }

    /** Pharmacist acknowledgement of an offline sale flagged with anomalies. */
    @Transactional
    public SaleEntity review(UUID saleId, String comment) {
        var sale = sales.findById(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        if (!sale.reviewRequired()) {
            throw new BusinessRuleException("not-under-review", "Sale " + sale.ticketNumber() + " needs no review");
        }
        sale.markReviewed();
        audit.record("SALE_REVIEWED", "SALE", saleId, Map.of(
                "anomalies", sale.anomalies().toString(), "comment", comment));
        return sale;
    }

    /** Money actually collected: neither the customer's account nor the insurer's share. */
    private static long paidCents(SaleEntity sale) {
        return sale.payments().stream()
                .filter(p -> p.method() != PaymentMethod.CREDIT && p.method() != PaymentMethod.THIRD_PARTY_PAYER)
                .mapToLong(SalePaymentEntity::amountCents).sum();
    }

    private boolean containsNarcotic(List<UUID> productIds) {
        return !productIds.isEmpty() && jdbc.sql(
                        "SELECT count(*) > 0 FROM product WHERE id = ANY(:ids) AND listing = 'STUPEFIANT'")
                .param("ids", productIds.toArray(UUID[]::new))
                .query(Boolean.class).single();
    }

    /** Units already put back per lot by earlier returns of this sale. */
    private Map<UUID, Integer> returnedByLot(UUID saleId) {
        Map<UUID, Integer> back = new HashMap<>();
        jdbc.sql("""
                        SELECT m.lot_id, sum(m.quantity) AS quantity
                        FROM stock_movement m JOIN sale_return r ON r.id = m.reference_id
                        WHERE m.movement_type = 'SALE_RETURN' AND r.sale_id = :sale AND m.lot_id IS NOT NULL
                        GROUP BY m.lot_id""")
                .param("sale", saleId)
                .query((RowCallbackHandler) rs -> back.put(rs.getObject("lot_id", UUID.class), rs.getInt("quantity")));
        return back;
    }
}
