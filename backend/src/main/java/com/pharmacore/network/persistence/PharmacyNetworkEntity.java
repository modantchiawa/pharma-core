package com.pharmacore.network.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Group of partner pharmacies (cooperative, territory...) sharing stock visibility, duty roster and chat. */
@Entity
@Table(name = "pharmacy_network")
public class PharmacyNetworkEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String region;

    @Column(name = "coordinator_tenant_id", nullable = false)
    private UUID coordinatorTenantId;

    @Column(name = "quantity_cap", nullable = false)
    private int quantityCap;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PharmacyNetworkEntity() {
    }

    public PharmacyNetworkEntity(String name, String region, UUID coordinatorTenantId, int quantityCap, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.region = region;
        this.coordinatorTenantId = coordinatorTenantId;
        this.quantityCap = quantityCap;
        this.createdAt = now;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public UUID coordinatorTenantId() {
        return coordinatorTenantId;
    }

    public int quantityCap() {
        return quantityCap;
    }
}
