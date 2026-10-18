package com.pharmacore.tenant.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A client pharmacy. Global table: readable by its own users, writable by the platform only (RLS). */
@Entity
@Table(name = "tenant")
public class TenantEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false, length = 63)
    private String slug;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TenantStatus status;

    @Column(name = "subscription_plan", nullable = false, length = 30)
    private String subscriptionPlan;

    @Column(name = "subscription_expires_at")
    private Instant subscriptionExpiresAt;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TenantEntity() {
    }

    public TenantEntity(UUID id, String slug, String legalName, String subscriptionPlan,
                        Instant subscriptionExpiresAt, String currency, String timeZone, Instant now) {
        this.id = id;
        this.slug = slug;
        this.legalName = legalName;
        this.status = TenantStatus.PROVISIONING;
        this.subscriptionPlan = subscriptionPlan;
        this.subscriptionExpiresAt = subscriptionExpiresAt;
        this.currency = currency;
        this.timeZone = timeZone;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changeStatus(TenantStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    public void renewSubscription(String plan, Instant expiresAt, Instant now) {
        this.subscriptionPlan = plan;
        this.subscriptionExpiresAt = expiresAt;
        this.updatedAt = now;
    }

    public boolean isOperational(Instant now) {
        return status == TenantStatus.ACTIVE
                && (subscriptionExpiresAt == null || subscriptionExpiresAt.isAfter(now));
    }

    public UUID id() {
        return id;
    }

    public String slug() {
        return slug;
    }

    public String legalName() {
        return legalName;
    }

    public TenantStatus status() {
        return status;
    }

    public String subscriptionPlan() {
        return subscriptionPlan;
    }

    public Instant subscriptionExpiresAt() {
        return subscriptionExpiresAt;
    }

    public String currency() {
        return currency;
    }

    public String timeZone() {
        return timeZone;
    }
}
