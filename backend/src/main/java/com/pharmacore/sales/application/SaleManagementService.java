package com.pharmacore.sales.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.customers.application.CustomerService;
import com.pharmacore.inventory.application.StockLedger;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleLineEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
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

    public SaleManagementService(SaleRepository sales, StockLedger ledger, AuditTrail audit, Clock clock,
                                 CustomerService customers, CashSessionService cashSessions,
                                 AccountingPoster accounting, TenantCalendar calendar, CashPolicyService policy,
                                 SupervisorApprovalService approvals) {
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

    /**
     * Cancels a sale: units go back into the exact lots they came from, the customer's credit is
     * released, the accounting entry is reversed and cash refunded leaves the current drawer.
     * A cancellation above the pharmacy's threshold, or of a sale whose drawer is already closed,
     * must be signed by a holder of {@code CASH_APPROVE} (the actor, or a supervisor's PIN).
     */
    @Transactional
    public SaleEntity cancel(UUID saleId, String reason, String terminalId,
                             SupervisorApprovalService.SupervisorApproval approval) {
        var sale = sales.findWithLines(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        if (sale.status() == SaleEntity.Status.CANCELLED) {
            throw new BusinessRuleException("already-cancelled", "Sale " + sale.ticketNumber() + " is already cancelled");
        }
        UUID userId = TenantContext.requireTenantUser().userId();
        Instant now = clock.instant();
        long threshold = policy.current().cancelApprovalThresholdCents();
        boolean aboveThreshold = threshold > 0 && sale.totalNetCents() > threshold;
        boolean drawerClosed = sale.cashSessionId() != null && cashSessions.isClosed(sale.cashSessionId());
        UUID approvedBy = null;
        if (aboveThreshold || drawerClosed) {
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
                .filter(p -> p.method() == com.pharmacore.sales.domain.PaymentMethod.CASH)
                .mapToLong(com.pharmacore.sales.persistence.SalePaymentEntity::amountCents).sum();
        if (cashRefund > 0 && terminalId != null) {
            cashSessions.sessionFor(terminalId, now).ifPresent(session -> cashSessions.recordRefund(session,
                    cashRefund, "Remboursement vente " + sale.ticketNumber()));
        }
        audit.record("SALE_CANCELLED", "SALE", saleId, Map.of(
                "ticket", sale.ticketNumber(),
                "netCents", Long.toString(sale.totalNetCents()),
                "reason", reason,
                "approvedBy", String.valueOf(approvedBy)));
        return sale;
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
}
