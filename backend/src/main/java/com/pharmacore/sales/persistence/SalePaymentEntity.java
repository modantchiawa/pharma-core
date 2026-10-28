package com.pharmacore.sales.persistence;

import com.pharmacore.sales.domain.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "sale_payment")
public class SalePaymentEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentMethod method;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(name = "third_party_code", length = 20, updatable = false)
    private String thirdPartyCode;

    @Column(length = 64, updatable = false)
    private String reference;

    @Column(length = 30, updatable = false)
    private String provider;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    /** Electronic payments wait for the operator statement before they count as settled. */
    public enum ConfirmationStatus {
        NOT_REQUIRED,
        PENDING,
        CONFIRMED,
        REJECTED
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_status", nullable = false, length = 15)
    private ConfirmationStatus confirmationStatus;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "rejection_reason", length = 255)
    private String rejectionReason;

    /** Where the confirmation came from: the operator's statement, or a notification received live. */
    public enum ConfirmationSource {
        STATEMENT,
        NOTIFICATION
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_source", length = 12)
    private ConfirmationSource confirmationSource;
    @Column(name = "insurer_id", updatable = false)
    private UUID insurerId;

    @Column(name = "policy_id", updatable = false)
    private UUID policyId;

    /** Set by the statement generator (SQL), never by the sale itself. */
    @Column(name = "statement_id", insertable = false, updatable = false)
    private UUID statementId;

    protected SalePaymentEntity() {
    }

    public SalePaymentEntity(PaymentMethod method, long amountCents, String thirdPartyCode, String reference,
                             String provider, UUID customerId) {
        this(method, amountCents, thirdPartyCode, reference, provider, customerId, null, null);
    }

    public SalePaymentEntity(PaymentMethod method, long amountCents, String thirdPartyCode, String reference,
                             String provider, UUID customerId, UUID insurerId, UUID policyId) {
        this.id = UUID.randomUUID();
        this.method = method;
        this.amountCents = amountCents;
        this.thirdPartyCode = thirdPartyCode;
        this.reference = reference;
        this.provider = provider;
        this.customerId = customerId;
        this.insurerId = insurerId;
        this.policyId = policyId;
        this.confirmationStatus = method == PaymentMethod.MOBILE_MONEY || method == PaymentMethod.CARD
                ? ConfirmationStatus.PENDING : ConfirmationStatus.NOT_REQUIRED;
    }

    public void confirm(UUID userId, Instant now) {
        confirm(userId, now, ConfirmationSource.STATEMENT);
    }

    public void confirm(UUID userId, Instant now, ConfirmationSource source) {
        requirePending();
        this.confirmationStatus = ConfirmationStatus.CONFIRMED;
        this.confirmedBy = userId;
        this.confirmedAt = now;
        this.confirmationSource = source;
    }

    public ConfirmationSource confirmationSource() {
        return confirmationSource;
    }

    public void reject(UUID userId, String reason, Instant now) {
        requirePending();
        this.confirmationStatus = ConfirmationStatus.REJECTED;
        this.confirmedBy = userId;
        this.confirmedAt = now;
        this.rejectionReason = reason;
    }

    private void requirePending() {
        if (confirmationStatus != ConfirmationStatus.PENDING) {
            throw new IllegalStateException("Payment is " + confirmationStatus);
        }
    }

    public UUID id() {
        return id;
    }

    public String reference() {
        return reference;
    }

    public ConfirmationStatus confirmationStatus() {
        return confirmationStatus;
    }

    public UUID confirmedBy() {
        return confirmedBy;
    }

    public Instant confirmedAt() {
        return confirmedAt;
    }

    public String rejectionReason() {
        return rejectionReason;
}

    public UUID insurerId() {
        return insurerId;
    }

    public UUID policyId() {
        return policyId;
    }

    public UUID statementId() {
        return statementId;
    }

    public String provider() {
        return provider;
    }

    public UUID customerId() {
        return customerId;
    }

    public PaymentMethod method() {
        return method;
    }

    public long amountCents() {
        return amountCents;
    }

    public String thirdPartyCode() {
        return thirdPartyCode;
    }
}
