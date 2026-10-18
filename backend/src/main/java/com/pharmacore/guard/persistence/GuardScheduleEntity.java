package com.pharmacore.guard.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Duty slot of a pharmacy within a network. Shared data: every member reads it, only the network
 * coordinator writes it (RLS). Overlapping slots for one pharmacy are refused by a PostgreSQL
 * exclusion constraint.
 */
@Entity
@Table(name = "guard_schedule")
public class GuardScheduleEntity {

    @Id
    private UUID id;

    @Column(name = "network_id", nullable = false, updatable = false)
    private UUID networkId;

    @Column(name = "pharmacy_tenant_id", nullable = false)
    private UUID pharmacyTenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "guard_type", nullable = false, length = 20)
    private GuardType guardType;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Version
    private long version;

    protected GuardScheduleEntity() {
    }

    public GuardScheduleEntity(UUID networkId, UUID pharmacyTenantId, GuardType guardType, Instant startsAt,
                               Instant endsAt, String notes, UUID createdBy) {
        this.id = UUID.randomUUID();
        this.networkId = networkId;
        this.createdBy = createdBy;
        reschedule(pharmacyTenantId, guardType, startsAt, endsAt, notes);
    }

    public final void reschedule(UUID pharmacyTenantId, GuardType guardType, Instant startsAt, Instant endsAt,
                                 String notes) {
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("A duty slot must end after it starts");
        }
        this.pharmacyTenantId = pharmacyTenantId;
        this.guardType = guardType;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.notes = notes;
    }

    public UUID id() {
        return id;
    }

    public UUID networkId() {
        return networkId;
    }

    public UUID pharmacyTenantId() {
        return pharmacyTenantId;
    }

    public GuardType guardType() {
        return guardType;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public String notes() {
        return notes;
    }

    public long version() {
        return version;
    }
}
