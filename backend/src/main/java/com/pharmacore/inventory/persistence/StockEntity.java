package com.pharmacore.inventory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Stock of one product in one pharmacy. Physical quantities live in {@link StockLotEntity};
 * this row carries settings, reservations and the shortfall counter, and is the lock target that
 * serializes every quantity change of the product.
 */
@Entity
@Table(name = "stock")
public class StockEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "selling_price_cents")
    private Long sellingPriceCents;

    @Column(name = "reorder_threshold", nullable = false)
    private int reorderThreshold;

    @Column(name = "reorder_quantity", nullable = false)
    private int reorderQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "shortfall_quantity", nullable = false)
    private int shortfallQuantity;

    @Column(length = 50)
    private String location;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StockEntity() {
    }

    public void configure(Long sellingPriceCents, int reorderThreshold, int reorderQuantity, String location,
                          Instant now) {
        this.sellingPriceCents = sellingPriceCents;
        this.reorderThreshold = reorderThreshold;
        this.reorderQuantity = reorderQuantity;
        this.location = location;
        touch(now);
    }

    public void reserve(int quantity, Instant now) {
        this.reservedQuantity += quantity;
        touch(now);
    }

    public void release(int quantity, Instant now) {
        if (quantity > reservedQuantity) {
            throw new IllegalStateException("Cannot release more than reserved");
        }
        this.reservedQuantity -= quantity;
        touch(now);
    }

    public void recordShortfall(int quantity, Instant now) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Shortfall must be positive");
        }
        this.shortfallQuantity += quantity;
        touch(now);
    }

    public void reduceShortfall(int quantity, Instant now) {
        if (quantity < 0 || quantity > shortfallQuantity) {
            throw new IllegalArgumentException("Invalid shortfall reduction " + quantity);
        }
        this.shortfallQuantity -= quantity;
        touch(now);
    }

    public int clearShortfall(Instant now) {
        int previous = shortfallQuantity;
        this.shortfallQuantity = 0;
        touch(now);
        return previous;
    }

    /** Bumps the change marker consumed by the POS delta feed. */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID productId() {
        return productId;
    }

    public Long sellingPriceCents() {
        return sellingPriceCents;
    }

    public int reorderThreshold() {
        return reorderThreshold;
    }

    public int reorderQuantity() {
        return reorderQuantity;
    }

    public int reservedQuantity() {
        return reservedQuantity;
    }

    public int shortfallQuantity() {
        return shortfallQuantity;
    }

    public String location() {
        return location;
    }
}
