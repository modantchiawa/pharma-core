package com.pharmacore.prescription.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * A prescription kept on file so that chronic treatments can be dispensed again without the
 * paper. {@code renewalsAllowed} is the total number of dispensings it permits, first included.
 */
@Entity
@Table(name = "prescription")
public class PrescriptionEntity {

    public enum Status {
        ACTIVE,
        EXHAUSTED,
        EXPIRED,
        CANCELLED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "prescriber_name", nullable = false, length = 150)
    private String prescriberName;

    @Column(name = "prescriber_identifier", length = 30)
    private String prescriberIdentifier;

    @Column(name = "patient_reference", length = 64)
    private String patientReference;

    @Column(name = "issued_on", nullable = false, updatable = false)
    private LocalDate issuedOn;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(name = "renewals_allowed", nullable = false)
    private short renewalsAllowed;

    @Column(name = "renewals_used", nullable = false)
    private short renewalsUsed;

    @Column(length = 500)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "prescription_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    private List<PrescriptionLineEntity> lines = new ArrayList<>();

    @Version
    private long version;

    protected PrescriptionEntity() {
    }

    public PrescriptionEntity(Details details, UUID createdBy, Instant now) {
        if (details.validUntil().isBefore(details.issuedOn())) {
            throw new IllegalArgumentException("Prescription validity ends before it was issued");
        }
        if (details.renewalsAllowed() < 1 || details.renewalsAllowed() > 24) {
            throw new IllegalArgumentException("A prescription allows 1 to 24 dispensings");
        }
        this.id = UUID.randomUUID();
        this.customerId = details.customerId();
        this.prescriberName = details.prescriberName();
        this.prescriberIdentifier = details.prescriberIdentifier();
        this.patientReference = details.patientReference();
        this.issuedOn = details.issuedOn();
        this.validUntil = details.validUntil();
        this.renewalsAllowed = (short) details.renewalsAllowed();
        this.renewalsUsed = 0;
        this.notes = details.notes();
        this.status = Status.ACTIVE;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void addLine(PrescriptionLineEntity line) {
        lines.add(line);
    }

    /** Why a dispensing cannot count against this prescription today, if any. */
    public Optional<String> refusalReason(LocalDate today) {
        if (status == Status.CANCELLED) {
            return Optional.of("Ordonnance annulée");
        }
        if (status == Status.EXHAUSTED || renewalsUsed >= renewalsAllowed) {
            return Optional.of("Ordonnance épuisée : " + renewalsAllowed + " délivrance(s) déjà effectuée(s)");
        }
        if (status == Status.EXPIRED || today.isAfter(validUntil)) {
            return Optional.of("Ordonnance expirée le " + validUntil);
        }
        return Optional.empty();
    }

    /** Counts one dispensing; marks the prescription exhausted on the last one. */
    public void dispense(LocalDate today) {
        refusalReason(today).ifPresent(reason -> {
            if (today.isAfter(validUntil) && status == Status.ACTIVE) {
                status = Status.EXPIRED;
            }
            throw new IllegalStateException(reason);
        });
        renewalsUsed++;
        if (renewalsUsed >= renewalsAllowed) {
            status = Status.EXHAUSTED;
        }
    }

    /** Lazily settles the status of a prescription whose validity ran out. */
    public boolean expireIfDue(LocalDate today) {
        if (status == Status.ACTIVE && today.isAfter(validUntil)) {
            status = Status.EXPIRED;
            return true;
        }
        return false;
    }

    public void cancel() {
        if (status == Status.CANCELLED) {
            throw new IllegalStateException("Prescription already cancelled");
        }
        status = Status.CANCELLED;
    }

    public UUID id() {
        return id;
    }

    public UUID customerId() {
        return customerId;
    }

    public String prescriberName() {
        return prescriberName;
    }

    public String prescriberIdentifier() {
        return prescriberIdentifier;
    }

    public String patientReference() {
        return patientReference;
    }

    public LocalDate issuedOn() {
        return issuedOn;
    }

    public LocalDate validUntil() {
        return validUntil;
    }

    public int renewalsAllowed() {
        return renewalsAllowed;
    }

    public int renewalsUsed() {
        return renewalsUsed;
    }

    public String notes() {
        return notes;
    }

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<PrescriptionLineEntity> lines() {
        return List.copyOf(lines);
    }

    public record Details(UUID customerId, String prescriberName, String prescriberIdentifier, String patientReference,
                          LocalDate issuedOn, LocalDate validUntil, int renewalsAllowed, String notes) {
    }
}
