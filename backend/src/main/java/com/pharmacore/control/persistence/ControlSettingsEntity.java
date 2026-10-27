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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Internal control settings of one pharmacy (one row, keyed by the tenant). */
@Entity
@Table(name = "control_settings")
public class ControlSettingsEntity {

    /** What happens when the person who created or sent an order receives it. */
    public enum Separation {
        OFF,
        WARN,
        BLOCK
    }

    /** Thresholds and switches the owner edits; amounts in minor units of the pharmacy currency. */
    public record Values(int microCountSize, long adjustmentApprovalCents, long refundApprovalCents,
                         long cancellationApprovalCents, long alertAdjustmentCents, long alertCancellationCents,
                         long alertCashVarianceCents, int pinFailureAlertCount, boolean blindReceiving,
                         Separation receivingSeparation, String activityHoursJson, boolean webhookEnabled,
                         String webhookUrl) {
    }

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "micro_count_size", nullable = false)
    private short microCountSize;

    @Column(name = "adjustment_approval_cents", nullable = false)
    private long adjustmentApprovalCents;

    @Column(name = "refund_approval_cents", nullable = false)
    private long refundApprovalCents;

    @Column(name = "cancellation_approval_cents", nullable = false)
    private long cancellationApprovalCents;

    @Column(name = "alert_adjustment_cents", nullable = false)
    private long alertAdjustmentCents;

    @Column(name = "alert_cancellation_cents", nullable = false)
    private long alertCancellationCents;

    @Column(name = "alert_cash_variance_cents", nullable = false)
    private long alertCashVarianceCents;

    @Column(name = "pin_failure_alert_count", nullable = false)
    private short pinFailureAlertCount;

    @Column(name = "blind_receiving", nullable = false)
    private boolean blindReceiving;

    @Enumerated(EnumType.STRING)
    @Column(name = "receiving_separation", nullable = false, length = 5)
    private Separation receivingSeparation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "activity_hours", nullable = false)
    private String activityHours;

    @Column(name = "webhook_enabled", nullable = false)
    private boolean webhookEnabled;

    @Column(name = "webhook_url", length = 500)
    private String webhookUrl;

    @Column(name = "webhook_secret", length = 128)
    private String webhookSecret;

    @Column(name = "selection_salt", nullable = false, updatable = false, length = 64)
    private String selectionSalt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ControlSettingsEntity() {
    }

    public ControlSettingsEntity(UUID tenantId, Values defaults, String selectionSalt, Instant now) {
        this.tenantId = tenantId;
        this.selectionSalt = selectionSalt;
        apply(defaults, null, now);
    }

    public final void apply(Values values, UUID by, Instant now) {
        this.microCountSize = (short) values.microCountSize();
        this.adjustmentApprovalCents = values.adjustmentApprovalCents();
        this.refundApprovalCents = values.refundApprovalCents();
        this.cancellationApprovalCents = values.cancellationApprovalCents();
        this.alertAdjustmentCents = values.alertAdjustmentCents();
        this.alertCancellationCents = values.alertCancellationCents();
        this.alertCashVarianceCents = values.alertCashVarianceCents();
        this.pinFailureAlertCount = (short) values.pinFailureAlertCount();
        this.blindReceiving = values.blindReceiving();
        this.receivingSeparation = values.receivingSeparation();
        this.activityHours = values.activityHoursJson();
        this.webhookEnabled = values.webhookEnabled();
        this.webhookUrl = values.webhookUrl();
        this.updatedBy = by;
        this.updatedAt = now;
    }

    public void setWebhookSecret(String secret) {
        this.webhookSecret = secret;
    }

    public Values values() {
        return new Values(microCountSize, adjustmentApprovalCents, refundApprovalCents, cancellationApprovalCents,
                alertAdjustmentCents, alertCancellationCents, alertCashVarianceCents, pinFailureAlertCount,
                blindReceiving, receivingSeparation, activityHours, webhookEnabled, webhookUrl);
    }

    public String webhookSecret() {
        return webhookSecret;
    }

    public String selectionSalt() {
        return selectionSalt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
