package com.pharmacore.sales.persistence;

import com.pharmacore.sales.domain.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    protected SalePaymentEntity() {
    }

    public SalePaymentEntity(PaymentMethod method, long amountCents, String thirdPartyCode, String reference,
                             String provider, UUID customerId) {
        this.id = UUID.randomUUID();
        this.method = method;
        this.amountCents = amountCents;
        this.thirdPartyCode = thirdPartyCode;
        this.reference = reference;
        this.provider = provider;
        this.customerId = customerId;
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
