package com.pharmacore.sales.persistence;

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

/** A cash drawer period on one terminal: opened with a float, closed with a count and a Z report. */
@Entity
@Table(name = "cash_session")
public class CashSessionEntity {

    public enum Status {
        OPEN,
        CLOSED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "terminal_id", nullable = false, updatable = false, length = 40)
    private String terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "opening_float_cents", nullable = false, updatable = false)
    private long openingFloatCents;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "expected_cash_cents")
    private Long expectedCashCents;

    @Column(name = "counted_cash_cents")
    private Long countedCashCents;

    @Column(name = "difference_cents")
    private Long differenceCents;

    @Column(length = 500)
    private String notes;

    @Column(name = "closing_requires_approval", nullable = false)
    private boolean closingRequiresApproval;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_note", length = 500)
    private String approvalNote;

    @Column(name = "previous_report_hash", length = 64)
    private String previousReportHash;

    @Column(name = "report_hash", length = 64)
    private String reportHash;

    @Version
    private long version;

    protected CashSessionEntity() {
    }

    public CashSessionEntity(String terminalId, UUID openedBy, long openingFloatCents, Instant now) {
        this.id = UUID.randomUUID();
        this.terminalId = terminalId;
        this.status = Status.OPEN;
        this.openedBy = openedBy;
        this.openedAt = now;
        this.openingFloatCents = openingFloatCents;
    }

    public void close(UUID closedBy, long expectedCents, long countedCents, String notes, Instant now) {
        if (status == Status.CLOSED) {
            throw new IllegalStateException("Session already closed");
        }
        this.status = Status.CLOSED;
        this.closedBy = closedBy;
        this.closedAt = now;
        this.expectedCashCents = expectedCents;
        this.countedCashCents = countedCents;
        this.differenceCents = countedCents - expectedCents;
        this.notes = notes;
    }

    /** Difference above the pharmacy's tolerance: a supervisor must sign the Z report. */
    public void requireApproval() {
        this.closingRequiresApproval = true;
    }

    public void approve(UUID supervisorId, String note, Instant now) {
        if (status != Status.CLOSED) {
            throw new IllegalStateException("Only a closed session can be approved");
        }
        this.approvedBy = supervisorId;
        this.approvedAt = now;
        this.approvalNote = note;
    }

    public void chain(String previousReportHash, String reportHash) {
        this.previousReportHash = previousReportHash;
        this.reportHash = reportHash;
    }

    public boolean closingRequiresApproval() {
        return closingRequiresApproval;
    }

    public UUID approvedBy() {
        return approvedBy;
    }

    public Instant approvedAt() {
        return approvedAt;
    }

    public String approvalNote() {
        return approvalNote;
    }

    public String previousReportHash() {
        return previousReportHash;
    }

    public String reportHash() {
        return reportHash;
    }

    public UUID id() {
        return id;
    }

    public String terminalId() {
        return terminalId;
    }

    public Status status() {
        return status;
    }

    public UUID openedBy() {
        return openedBy;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public long openingFloatCents() {
        return openingFloatCents;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public Long expectedCashCents() {
        return expectedCashCents;
    }

    public Long countedCashCents() {
        return countedCashCents;
    }

    public Long differenceCents() {
        return differenceCents;
    }

    public String notes() {
        return notes;
    }
}
