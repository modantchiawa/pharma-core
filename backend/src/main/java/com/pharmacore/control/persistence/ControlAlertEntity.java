package com.pharmacore.control.persistence;

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
 * An alert raised for the owner. It names staff and amounts, never a customer or a patient,
 * because it may leave the pharmacy through the webhook.
 */
@Entity
@Table(name = "control_alert")
public class ControlAlertEntity {

    public enum Kind {
        LARGE_ADJUSTMENT,
        CANCELLATION_AFTER_PAYMENT,
        CASH_VARIANCE,
        COUNT_DISCREPANCY,
        OUTSIDE_HOURS,
        PIN_FAILURES,
        RECEIPT_DISCREPANCY,
        /** A pharmacist dispensed despite a contraindication found at the counter. */
        SAFETY_OVERRIDE,
        /** The evening figures of the pharmacy, for the owner's phone. */
        DAILY_DIGEST,
        /** Sent from the settings screen to check the bell and the webhook. */
        TEST
    }

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /** What a rule found, before it becomes an alert. */
    public record Draft(Kind kind, Severity severity, String title, String detail, String resourceType,
                        UUID resourceId, UUID staffUserId, Long amountCents, Instant occurredAt) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 8)
    private Severity severity;

    @Column(nullable = false, updatable = false, length = 150)
    private String title;

    @Column(nullable = false, updatable = false, length = 500)
    private String detail;

    @Column(name = "resource_type", updatable = false, length = 40)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "staff_user_id", updatable = false)
    private UUID staffUserId;

    @Column(name = "amount_cents", updatable = false)
    private Long amountCents;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Version
    private long version;

    protected ControlAlertEntity() {
    }

    public ControlAlertEntity(Draft draft, Instant now) {
        this.id = UUID.randomUUID();
        this.kind = draft.kind();
        this.severity = draft.severity();
        this.title = truncate(draft.title(), 150);
        this.detail = truncate(draft.detail(), 500);
        this.resourceType = draft.resourceType();
        this.resourceId = draft.resourceId();
        this.staffUserId = draft.staffUserId();
        this.amountCents = draft.amountCents();
        this.occurredAt = draft.occurredAt();
        this.createdAt = now;
    }

    /** @return false when it was already acknowledged */
    public boolean acknowledge(UUID by, Instant now) {
        if (acknowledgedAt != null) {
            return false;
        }
        this.acknowledgedAt = now;
        this.acknowledgedBy = by;
        return true;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public UUID id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public Severity severity() {
        return severity;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    public String resourceType() {
        return resourceType;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public UUID staffUserId() {
        return staffUserId;
    }

    public Long amountCents() {
        return amountCents;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant acknowledgedAt() {
        return acknowledgedAt;
    }

    public UUID acknowledgedBy() {
        return acknowledgedBy;
    }
}
