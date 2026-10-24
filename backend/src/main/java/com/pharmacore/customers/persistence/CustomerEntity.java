package com.pharmacore.customers.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Customer file with an optional credit account ("ardoise") and loyalty points. */
@Entity
@Table(name = "customer")
public class CustomerEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(length = 300)
    private String address;

    @Column(name = "credit_limit_cents", nullable = false)
    private long creditLimitCents;

    @Column(name = "credit_balance_cents", nullable = false)
    private long creditBalanceCents;

    @Column(name = "loyalty_points", nullable = false)
    private int loyaltyPoints;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected CustomerEntity() {
    }

    public CustomerEntity(String code, Details details, Instant now) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.active = true;
        this.createdAt = now;
        update(details);
    }

    public final void update(Details details) {
        this.displayName = details.displayName();
        this.phone = details.phone();
        this.email = details.email();
        this.address = details.address();
        this.creditLimitCents = details.creditLimitCents();
        this.notes = details.notes();
    }

    /** @return true when the new balance stays within the credit limit */
    public boolean addCredit(long cents) {
        this.creditBalanceCents += cents;
        return creditBalanceCents <= creditLimitCents;
    }

    public void settle(long cents) {
        this.creditBalanceCents -= cents;
    }

    public void addLoyaltyPoints(int points) {
        this.loyaltyPoints += points;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public String phone() {
        return phone;
    }

    public long creditLimitCents() {
        return creditLimitCents;
    }

    public long creditBalanceCents() {
        return creditBalanceCents;
    }

    public int loyaltyPoints() {
        return loyaltyPoints;
    }

    public boolean active() {
        return active;
    }

    public Details details() {
        return new Details(displayName, phone, email, address, creditLimitCents, notes);
    }

    public record Details(String displayName, String phone, String email, String address, long creditLimitCents,
                          String notes) {
    }
}
