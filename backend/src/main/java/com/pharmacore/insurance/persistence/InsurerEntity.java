package com.pharmacore.insurance.persistence;

import com.pharmacore.insurance.domain.InsurerKind;
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

/** Third-party payer the pharmacy has an agreement with; owns a dedicated receivable account. */
@Entity
@Table(name = "insurer")
public class InsurerEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private InsurerKind kind;

    @Column(name = "default_coverage_rate", nullable = false)
    private short defaultCoverageRate;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(length = 300)
    private String address;

    @Column(name = "payment_terms_days", nullable = false)
    private short paymentTermsDays;

    @Column(name = "account_code", nullable = false, updatable = false, length = 10)
    private String accountCode;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected InsurerEntity() {
    }

    public InsurerEntity(String code, String accountCode, Details details, Instant now) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.accountCode = accountCode;
        this.active = true;
        this.createdAt = now;
        update(details);
    }

    public final void update(Details details) {
        this.name = details.name();
        this.kind = details.kind();
        this.defaultCoverageRate = (short) details.defaultCoverageRate();
        this.contactName = details.contactName();
        this.phone = details.phone();
        this.email = details.email();
        this.address = details.address();
        this.paymentTermsDays = (short) details.paymentTermsDays();
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

    public String name() {
        return name;
    }

    public String accountCode() {
        return accountCode;
    }

    public boolean active() {
        return active;
    }

    public int defaultCoverageRate() {
        return defaultCoverageRate;
    }

    public Details details() {
        return new Details(name, kind, defaultCoverageRate, contactName, phone, email, address, paymentTermsDays);
    }

    public record Details(String name, InsurerKind kind, int defaultCoverageRate, String contactName, String phone,
                          String email, String address, int paymentTermsDays) {
    }
}
