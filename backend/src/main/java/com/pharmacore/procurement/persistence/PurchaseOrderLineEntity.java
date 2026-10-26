package com.pharmacore.procurement.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLineEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    @Column(name = "unit_cost_cents", nullable = false)
    private long unitCostCents;

    @Column(name = "pack_size", nullable = false)
    private short packSize;

    @Column(name = "suggested_quantity")
    private Integer suggestedQuantity;

    @Column(length = 255)
    private String reason;

    protected PurchaseOrderLineEntity() {
    }

    public PurchaseOrderLineEntity(short lineNo, UUID productId, int quantityOrdered, long unitCostCents, int packSize,
                                   Integer suggestedQuantity, String reason) {
        this.id = UUID.randomUUID();
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantityOrdered = quantityOrdered;
        this.unitCostCents = unitCostCents;
        this.packSize = (short) Math.max(packSize, 1);
        this.suggestedQuantity = suggestedQuantity;
        this.reason = reason;
    }

    void receive(int quantity) {
        this.quantityReceived += quantity;
    }

    boolean complete() {
        return quantityReceived >= quantityOrdered;
    }

    public short lineNo() {
        return lineNo;
    }

    public UUID productId() {
        return productId;
    }

    public int quantityOrdered() {
        return quantityOrdered;
    }

    public int quantityReceived() {
        return quantityReceived;
    }

    public long unitCostCents() {
        return unitCostCents;
    }

    public int packSize() {
        return packSize;
    }

    public Integer suggestedQuantity() {
        return suggestedQuantity;
    }

    public String reason() {
        return reason;
    }
}
