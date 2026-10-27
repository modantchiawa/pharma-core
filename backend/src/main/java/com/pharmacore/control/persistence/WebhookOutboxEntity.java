package com.pharmacore.control.persistence;

import com.pharmacore.control.domain.DeliveryBackoff;
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
 * One alert waiting to be posted to the owner's webhook. Written in the transaction that raises
 * the alert, delivered later by the dispatcher, so an alert raised while the hub is offline is
 * sent once the connection is back.
 */
@Entity
@Table(name = "control_webhook_outbox")
public class WebhookOutboxEntity {

    public enum Status {
        PENDING,
        DELIVERED,
        FAILED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "alert_id", nullable = false, updatable = false)
    private UUID alertId;

    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private short attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Version
    private long version;

    protected WebhookOutboxEntity() {
    }

    public WebhookOutboxEntity(UUID alertId, String payload, Instant now) {
        this.id = UUID.randomUUID();
        this.alertId = alertId;
        this.payload = payload;
        this.status = Status.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    /** Taken by a dispatcher: no other one picks it before {@code until}, even if this one dies. */
    public void lease(Instant until) {
        this.nextAttemptAt = until;
    }

    public void delivered(Instant now) {
        this.attempts++;
        this.status = Status.DELIVERED;
        this.deliveredAt = now;
        this.lastError = null;
    }

    /** A failed attempt: retried after the backoff delay, or given up when exhausted. */
    public void failed(String error, Instant now) {
        this.attempts++;
        this.lastError = error == null ? null : error.length() <= 300 ? error : error.substring(0, 299) + "…";
        if (DeliveryBackoff.exhausted(attempts)) {
            this.status = Status.FAILED;
        } else {
            this.nextAttemptAt = now.plus(DeliveryBackoff.delayAfter(attempts));
        }
    }

    public UUID id() {
        return id;
    }

    public UUID alertId() {
        return alertId;
    }

    public String payload() {
        return payload;
    }

    public Status status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant deliveredAt() {
        return deliveredAt;
    }
}
