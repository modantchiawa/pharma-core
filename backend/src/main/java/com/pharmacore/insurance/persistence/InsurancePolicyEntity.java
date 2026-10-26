package com.pharmacore.insurance.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** A customer's cover with an insurer: rate, optional monthly ceiling, validity window. */
@Entity
@Table(name = "insurance_policy")
public class InsurancePolicyEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "insurer_id", nullable = false, updatable = false)
    private UUID insurerId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "policy_number", nullable = false, length = 60)
    private String policyNumber;

    @Column(name = "beneficiary_name", nullable = false, length = 150)
    private String beneficiaryName;

    @Column(name = "coverage_rate", nullable = false)
    private short coverageRate;

    @Column(name = "monthly_ceiling_cents")
    private Long monthlyCeilingCents;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(nullable = false)
    private boolean active;

    @Version
    private long version;

    protected InsurancePolicyEntity() {
    }

    public InsurancePolicyEntity(UUID insurerId, UUID customerId, Details details) {
        this.id = UUID.randomUUID();
        this.insurerId = insurerId;
        this.customerId = customerId;
        this.active = true;
        update(details);
    }

    public final void update(Details details) {
        if (details.validUntil() != null && details.validUntil().isBefore(details.validFrom())) {
            throw new IllegalArgumentException("Policy end precedes its start");
        }
        this.policyNumber = details.policyNumber();
        this.beneficiaryName = details.beneficiaryName();
        this.coverageRate = (short) details.coverageRate();
        this.monthlyCeilingCents = details.monthlyCeilingCents();
        this.validFrom = details.validFrom();
        this.validUntil = details.validUntil();
        this.active = details.active();
    }

    /** Active and inside its validity window on {@code day}. */
    public boolean isValidOn(LocalDate day) {
        return active && !day.isBefore(validFrom) && (validUntil == null || !day.isAfter(validUntil));
    }

    public UUID id() {
        return id;
    }

    public UUID insurerId() {
        return insurerId;
    }

    public UUID customerId() {
        return customerId;
    }

    public String policyNumber() {
        return policyNumber;
    }

    public String beneficiaryName() {
        return beneficiaryName;
    }

    public int coverageRate() {
        return coverageRate;
    }

    public Long monthlyCeilingCents() {
        return monthlyCeilingCents;
    }

    public LocalDate validFrom() {
        return validFrom;
    }

    public LocalDate validUntil() {
        return validUntil;
    }

    public boolean active() {
        return active;
    }

    public record Details(String policyNumber, String beneficiaryName, int coverageRate, Long monthlyCeilingCents,
                          LocalDate validFrom, LocalDate validUntil, boolean active) {
    }
}
