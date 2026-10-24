package com.pharmacore.sales.application;

import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity.ConfirmationStatus;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mobile money and card receipts are only promises until the operator's statement confirms
 * them. The pharmacist ticks each transaction against the Airtel / Moov / Orange statement; a
 * rejected one puts the sale under review. A transaction reference can justify one payment only
 * (unique index {@code ux_sale_payment_reference}).
 */
@Service
public class PaymentReconciliationService {

    public record PendingPayment(UUID paymentId, UUID saleId, String ticketNumber, Instant occurredAt, String method,
                                 String provider, String reference, long amountCents, String status,
                                 String rejectionReason) {
    }

    private final SaleRepository sales;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PaymentReconciliationService(SaleRepository sales, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.sales = sales;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Refuses a transaction reference already used by a payment of this pharmacy. */
    @Transactional(readOnly = true)
    public void assertReferenceUnused(String provider, String reference) {
        if (provider == null || reference == null || reference.isBlank()) {
            return;
        }
        boolean used = jdbc.sql("""
                        SELECT exists(SELECT 1 FROM sale_payment
                                      WHERE tenant_id = :tenant AND provider = :provider AND reference = :reference)""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("provider", provider)
                .param("reference", reference.trim())
                .query(Boolean.class).single();
        if (used) {
            throw new BusinessRuleException("duplicate-payment-reference",
                    "La référence " + reference + " (" + provider + ") a déjà justifié un paiement");
        }
    }

    @Transactional(readOnly = true)
    public List<PendingPayment> list(ConfirmationStatus status, String provider, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT p.id, p.sale_id, s.ticket_number, s.occurred_at, p.method, p.provider, p.reference,
                               p.amount_cents, p.confirmation_status, p.rejection_reason
                        FROM sale_payment p
                        JOIN sale s ON s.id = p.sale_id
                        WHERE p.confirmation_status = :status
                          AND (:provider::text IS NULL OR p.provider = :provider::text)
                          AND s.occurred_at >= :from AND s.occurred_at < :to
                          AND s.status = 'COMPLETED'
                        ORDER BY s.occurred_at
                        LIMIT :limit""")
                .param("status", status.name())
                .param("provider", provider == null || provider.isBlank() ? null : provider)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, rowNum) -> new PendingPayment(rs.getObject("id", UUID.class),
                        rs.getObject("sale_id", UUID.class), rs.getString("ticket_number"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("method"), rs.getString("provider"),
                        rs.getString("reference"), rs.getLong("amount_cents"), rs.getString("confirmation_status"),
                        rs.getString("rejection_reason")))
                .list();
    }

    @Transactional
    public PendingPayment confirm(UUID paymentId) {
        var user = TenantContext.requireTenantUser();
        var located = locate(paymentId);
        located.payment().confirm(user.userId(), clock.instant());
        audit.record("PAYMENT_CONFIRMED", "SALE_PAYMENT", paymentId, Map.of(
                "sale", located.sale().ticketNumber(),
                "provider", String.valueOf(located.payment().provider()),
                "reference", String.valueOf(located.payment().reference()),
                "amountCents", Long.toString(located.payment().amountCents())));
        return view(located);
    }

    @Transactional
    public PendingPayment reject(UUID paymentId, String reason) {
        var user = TenantContext.requireTenantUser();
        var located = locate(paymentId);
        located.payment().reject(user.userId(), reason, clock.instant());
        located.sale().requireReview();
        audit.record("PAYMENT_REJECTED", "SALE_PAYMENT", paymentId, Map.of(
                "sale", located.sale().ticketNumber(),
                "provider", String.valueOf(located.payment().provider()),
                "reference", String.valueOf(located.payment().reference()),
                "amountCents", Long.toString(located.payment().amountCents()),
                "reason", reason));
        return view(located);
    }

    private record Located(SaleEntity sale, SalePaymentEntity payment) {
    }

    private Located locate(UUID paymentId) {
        UUID saleId = jdbc.sql("SELECT sale_id FROM sale_payment WHERE id = :id")
                .param("id", paymentId).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
        var sale = sales.findWithLines(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        var payment = sale.payments().stream().filter(p -> p.id().equals(paymentId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
        if (payment.confirmationStatus() != ConfirmationStatus.PENDING) {
            throw new BusinessRuleException("payment-not-pending", "Ce paiement est déjà " + payment.confirmationStatus());
        }
        return new Located(sale, payment);
    }

    private static PendingPayment view(Located located) {
        var p = located.payment();
        return new PendingPayment(p.id(), located.sale().id(), located.sale().ticketNumber(), located.sale().occurredAt(),
                p.method().name(), p.provider(), p.reference(), p.amountCents(), p.confirmationStatus().name(),
                p.rejectionReason());
    }
}
