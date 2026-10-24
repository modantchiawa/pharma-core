package com.pharmacore.procurement.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** A product as sold by a given supplier: reference, last purchase price, pack size. */
@Entity
@Table(name = "supplier_product")
public class SupplierProductEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "supplier_reference", length = 40)
    private String supplierReference;

    @Column(name = "purchase_price_cents")
    private Long purchasePriceCents;

    @Column(name = "pack_size", nullable = false)
    private short packSize;

    @Column(nullable = false)
    private boolean preferred;

    @Column(name = "last_purchase_at")
    private Instant lastPurchaseAt;

    @Version
    private long version;

    protected SupplierProductEntity() {
    }

    public SupplierProductEntity(UUID supplierId, UUID productId, String supplierReference, Long purchasePriceCents,
                                 int packSize, boolean preferred) {
        this.id = UUID.randomUUID();
        this.supplierId = supplierId;
        this.productId = productId;
        update(supplierReference, purchasePriceCents, packSize, preferred);
    }

    public final void update(String supplierReference, Long purchasePriceCents, int packSize, boolean preferred) {
        this.supplierReference = supplierReference;
        this.purchasePriceCents = purchasePriceCents;
        this.packSize = (short) Math.max(packSize, 1);
        this.preferred = preferred;
    }

    public void recordPurchase(long unitCostCents, Instant at) {
        this.purchasePriceCents = unitCostCents;
        this.lastPurchaseAt = at;
    }

    public UUID supplierId() {
        return supplierId;
    }

    public UUID productId() {
        return productId;
    }

    public String supplierReference() {
        return supplierReference;
    }

    public Long purchasePriceCents() {
        return purchasePriceCents;
    }

    public int packSize() {
        return packSize;
    }

    public boolean preferred() {
        return preferred;
    }
}
