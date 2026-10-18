package com.pharmacore.inventory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** Append-only stock ledger: every quantity change is explained by exactly one movement. */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovementEntity {

    public enum Type {
        RECEIPT,
        SALE,
        SALE_SHORTFALL,
        SALE_CANCELLATION,
        ADJUSTMENT,
        TRANSFER_OUT,
        RESERVATION,
        RESERVATION_RELEASE
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "stock_id", nullable = false)
    private UUID stockId;

    @Column(name = "lot_id")
    private UUID lotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 30)
    private Type type;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "reference_type", length = 40)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    protected StockMovementEntity() {
    }

    public StockMovementEntity(UUID stockId, UUID lotId, Type type, int quantity, String referenceType,
                               UUID referenceId, Instant occurredAt, UUID recordedBy) {
        this.id = UUID.randomUUID();
        this.stockId = stockId;
        this.lotId = lotId;
        this.type = type;
        this.quantity = quantity;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.occurredAt = occurredAt;
        this.recordedBy = recordedBy;
    }

    public UUID lotId() {
        return lotId;
    }

    public Type type() {
        return type;
    }

    public int quantity() {
        return quantity;
    }
}
