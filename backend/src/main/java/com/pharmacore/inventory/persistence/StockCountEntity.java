package com.pharmacore.inventory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * A physical inventory session: expected quantities frozen at start, counted blind, variances
 * applied at validation.
 */
@Entity
@Table(name = "stock_count")
public class StockCountEntity {

    public enum Status {
        OPEN,
        VALIDATED,
        CANCELLED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false, length = 150)
    private String label;

    @Column(length = 50, updatable = false)
    private String location;

    @Column(name = "started_by", nullable = false, updatable = false)
    private UUID startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "validated_by")
    private UUID validatedBy;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(length = 500)
    private String notes;

    @Version
    private long version;

    protected StockCountEntity() {
    }

    public StockCountEntity(String label, String location, UUID startedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.status = Status.OPEN;
        this.label = label;
        this.location = location;
        this.startedBy = startedBy;
        this.startedAt = now;
    }

    public void validate(UUID userId, String notes, Instant now) {
        requireOpen();
        this.status = Status.VALIDATED;
        this.validatedBy = userId;
        this.validatedAt = now;
        this.notes = notes;
    }

    public void cancel(UUID userId, String notes, Instant now) {
        requireOpen();
        this.status = Status.CANCELLED;
        this.validatedBy = userId;
        this.validatedAt = now;
        this.notes = notes;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new IllegalStateException("Stock count " + label + " is " + status);
        }
    }

    public UUID id() {
        return id;
    }

    public Status status() {
        return status;
    }

    public String label() {
        return label;
    }

    public String location() {
        return location;
    }

    public UUID startedBy() {
        return startedBy;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public UUID validatedBy() {
        return validatedBy;
    }

    public Instant validatedAt() {
        return validatedAt;
    }

    public String notes() {
        return notes;
    }
}
