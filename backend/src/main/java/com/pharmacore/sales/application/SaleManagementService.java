package com.pharmacore.sales.application;

import com.pharmacore.inventory.application.StockLedger;
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

    public SaleManagementService(SaleRepository sales, StockLedger ledger, AuditTrail audit, Clock clock) {
        this.sales = sales;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
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

    /** Cancels a sale and puts every unit back into the exact lot it was taken from. */
    @Transactional
    public SaleEntity cancel(UUID saleId, String reason) {
        var sale = sales.findWithLines(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        if (sale.status() == SaleEntity.Status.CANCELLED) {
            throw new BusinessRuleException("already-cancelled", "Sale " + sale.ticketNumber() + " is already cancelled");
        }
        UUID userId = TenantContext.requireTenantUser().userId();
        Instant now = clock.instant();
        var reference = new StockLedger.Reference("SALE_CANCELLATION", saleId, now, userId);
        for (SaleLineEntity line : sale.lines()) {
            ledger.restore(line.productId(), line.lotAllocations(), line.uncoveredQuantity(), reference);
        }
        sale.cancel(userId, reason, now);
        audit.record("SALE_CANCELLED", "SALE", saleId, Map.of(
                "ticket", sale.ticketNumber(),
                "netCents", Long.toString(sale.totalNetCents()),
                "reason", reason));
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
