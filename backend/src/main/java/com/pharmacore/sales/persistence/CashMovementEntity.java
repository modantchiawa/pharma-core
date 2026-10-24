package com.pharmacore.sales.persistence;

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

/** Cash entering or leaving the drawer outside of sales (bank deposit, owner float, expense). */
@Entity
@Immutable
@Table(name = "cash_movement")
public class CashMovementEntity {

    public enum Kind {
        CASH_IN,
        CASH_OUT,
        EXPENSE
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "cash_session_id", nullable = false)
    private UUID cashSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false)
    private String reason;

    @Column(name = "expense_account", length = 10)
    private String expenseAccount;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected CashMovementEntity() {
    }

    public CashMovementEntity(UUID cashSessionId, Kind kind, long amountCents, String reason, String expenseAccount,
                              UUID recordedBy, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.cashSessionId = cashSessionId;
        this.kind = kind;
        this.amountCents = amountCents;
        this.reason = reason;
        this.expenseAccount = expenseAccount;
        this.recordedBy = recordedBy;
        this.occurredAt = occurredAt;
    }

    public UUID id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public long amountCents() {
        return amountCents;
    }

    public String reason() {
        return reason;
    }

    public String expenseAccount() {
        return expenseAccount;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
