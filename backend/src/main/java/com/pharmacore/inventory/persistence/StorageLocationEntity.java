package com.pharmacore.inventory.persistence;

import com.pharmacore.inventory.domain.StorageLocationKind;
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
 * A place where products are stored (shelf, drawer, fridge, narcotics safe…). Never deleted:
 * a location that is no longer used is deactivated once it is empty. Every change bumps
 * {@code updated_at}, which re-emits its products to the offline POS cache.
 */
@Entity
@Table(name = "storage_location")
public class StorageLocationEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StorageLocationKind kind;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StorageLocationEntity() {
    }

    public StorageLocationEntity(Details details, Instant now) {
        this.id = UUID.randomUUID();
        this.active = true;
        update(details, now);
    }

    public final void update(Details details, Instant now) {
        this.code = details.code();
        this.name = details.name();
        this.kind = details.kind();
        this.sortOrder = details.sortOrder();
        this.updatedAt = now;
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public boolean active() {
        return active;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** "A3 · Rayon A, étagère 3", as shown in messages and audit details. */
    public String label() {
        return code + " · " + name;
    }

    public Details details() {
        return new Details(code, name, kind, sortOrder);
    }

    public record Details(String code, String name, StorageLocationKind kind, int sortOrder) {
    }
}
