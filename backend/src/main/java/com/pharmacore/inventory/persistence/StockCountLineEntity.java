package com.pharmacore.inventory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** One lot to count. The expected quantity is the shelf quantity when the count started. */
@Entity
@Table(name = "stock_count_line")
public class StockCountLineEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "count_id", nullable = false, updatable = false)
    private UUID countId;

    @Column(name = "stock_id", nullable = false, updatable = false)
    private UUID stockId;

    @Column(name = "lot_id", nullable = false, updatable = false)
    private UUID lotId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "lot_number", nullable = false, updatable = false, length = 40)
    private String lotNumber;

    @Column(name = "expiry_date", nullable = false, updatable = false)
    private LocalDate expiryDate;

    @Column(name = "expected_quantity", nullable = false, updatable = false)
    private int expectedQuantity;

    @Column(name = "counted_quantity")
    private Integer countedQuantity;

    @Column(name = "counted_by")
    private UUID countedBy;

    @Column(name = "counted_at")
    private Instant countedAt;

    protected StockCountLineEntity() {
    }

    public void count(int quantity, UUID userId, Instant now) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Counted quantity cannot be negative");
        }
        this.countedQuantity = quantity;
        this.countedBy = userId;
        this.countedAt = now;
    }

    public boolean counted() {
        return countedQuantity != null;
    }

    /** Signed variance, zero while the line is not counted. */
    public int variance() {
        return countedQuantity == null ? 0 : countedQuantity - expectedQuantity;
    }

    public UUID id() {
        return id;
    }

    public UUID countId() {
        return countId;
    }

    public UUID stockId() {
        return stockId;
    }

    public UUID lotId() {
        return lotId;
    }

    public UUID productId() {
        return productId;
    }

    public String lotNumber() {
        return lotNumber;
    }

    public LocalDate expiryDate() {
        return expiryDate;
    }

    public int expectedQuantity() {
        return expectedQuantity;
    }

    public Integer countedQuantity() {
        return countedQuantity;
    }

    public Instant countedAt() {
        return countedAt;
    }
}
