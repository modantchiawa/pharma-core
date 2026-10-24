package com.pharmacore.sales.persistence;

import com.pharmacore.sales.domain.SaleAnomaly;
import com.pharmacore.sales.domain.SaleOrigin;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "sale")
public class SaleEntity {

    public enum Status {
        COMPLETED,
        CANCELLED
    }

    /** Legal e-invoicing (e.g. DGI Niger / SECeF): none required, waiting for the connector, done, or failed. */
    public enum CertificationStatus {
        NOT_REQUIRED,
        PENDING,
        CERTIFIED,
        FAILED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "client_sale_id", nullable = false, updatable = false)
    private UUID clientSaleId;

    @Column(name = "terminal_id", nullable = false, updatable = false, length = 40)
    private String terminalId;

    @Column(name = "ticket_number", nullable = false, updatable = false, length = 40)
    private String ticketNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SaleOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "review_required", nullable = false)
    private boolean reviewRequired;

    @Column(length = 500)
    private String anomalies;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "cashier_user_id", nullable = false, updatable = false)
    private UUID cashierUserId;

    @Column(name = "total_gross_cents", nullable = false, updatable = false)
    private long totalGrossCents;

    @Column(name = "total_discount_cents", nullable = false, updatable = false)
    private long totalDiscountCents;

    @Column(name = "total_net_cents", nullable = false, updatable = false)
    private long totalNetCents;

    @Column(name = "prescriber_name", length = 150, updatable = false)
    private String prescriberName;

    @Column(name = "prescriber_rpps", length = 11, updatable = false)
    private String prescriberRpps;

    @Column(name = "patient_reference", length = 64, updatable = false)
    private String patientReference;

    @Column(name = "ordonnancier_number", updatable = false)
    private Long ordonnancierNumber;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "cash_session_id", updatable = false)
    private UUID cashSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "certification_status", nullable = false, length = 12)
    private CertificationStatus certificationStatus;

    @Column(name = "certification_code", length = 64)
    private String certificationCode;

    @Column(name = "certification_qr")
    private String certificationQr;

    @Column(name = "certified_at")
    private Instant certifiedAt;

    @Column(name = "certification_error")
    private String certificationError;

    /** Renewable prescription on file this dispensing counts against. */
    @Column(name = "prescription_id")
    private UUID prescriptionId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "sale_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    private List<SaleLineEntity> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "sale_id", nullable = false, updatable = false)
    private List<SalePaymentEntity> payments = new ArrayList<>();

    @Version
    private long version;

    protected SaleEntity() {
    }

    public SaleEntity(UUID id, UUID clientSaleId, String terminalId, String ticketNumber, SaleOrigin origin,
                      Instant occurredAt, Instant recordedAt, UUID cashierUserId, long totalGrossCents,
                      long totalDiscountCents, long totalNetCents, String prescriberName, String prescriberRpps,
                      String patientReference, Long ordonnancierNumber, Set<SaleAnomaly> anomalies,
                      UUID customerId, UUID cashSessionId, boolean certificationRequired) {
        this.id = id;
        this.customerId = customerId;
        this.cashSessionId = cashSessionId;
        this.certificationStatus = certificationRequired ? CertificationStatus.PENDING : CertificationStatus.NOT_REQUIRED;
        this.clientSaleId = clientSaleId;
        this.terminalId = terminalId;
        this.ticketNumber = ticketNumber;
        this.origin = origin;
        this.status = Status.COMPLETED;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.cashierUserId = cashierUserId;
        this.totalGrossCents = totalGrossCents;
        this.totalDiscountCents = totalDiscountCents;
        this.totalNetCents = totalNetCents;
        this.prescriberName = prescriberName;
        this.prescriberRpps = prescriberRpps;
        this.patientReference = patientReference;
        this.ordonnancierNumber = ordonnancierNumber;
        this.reviewRequired = !anomalies.isEmpty();
        this.anomalies = anomalies.isEmpty() ? null
                : anomalies.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public void addLine(SaleLineEntity line) {
        lines.add(line);
    }

    public void addPayment(SalePaymentEntity payment) {
        payments.add(payment);
    }

    public void cancel(UUID userId, String reason, Instant now) {
        if (status == Status.CANCELLED) {
            throw new IllegalStateException("Sale already cancelled");
        }
        this.status = Status.CANCELLED;
        this.cancelledBy = userId;
        this.cancelReason = reason;
        this.cancelledAt = now;
    }

    public void markReviewed() {
        this.reviewRequired = false;
    }

    public void attachPrescription(UUID prescriptionId) {
        if (this.prescriptionId != null && !this.prescriptionId.equals(prescriptionId)) {
            throw new IllegalStateException("Sale already attached to another prescription");
        }
        this.prescriptionId = prescriptionId;
    }

    public UUID prescriptionId() {
        return prescriptionId;
    }

    public String prescriberName() {
        return prescriberName;
    }

    public String patientReference() {
        return patientReference;
    }

    public void certified(String code, String qrPayload, Instant at) {
        this.certificationStatus = CertificationStatus.CERTIFIED;
        this.certificationCode = code;
        this.certificationQr = qrPayload;
        this.certifiedAt = at;
        this.certificationError = null;
    }

    public void certificationFailed(String error, boolean permanent) {
        this.certificationStatus = permanent ? CertificationStatus.FAILED : CertificationStatus.PENDING;
        this.certificationError = error == null ? null : error.substring(0, Math.min(error.length(), 255));
    }

    public UUID customerId() {
        return customerId;
    }

    public UUID cashSessionId() {
        return cashSessionId;
    }

    public CertificationStatus certificationStatus() {
        return certificationStatus;
    }

    public String certificationCode() {
        return certificationCode;
    }

    public String certificationQr() {
        return certificationQr;
    }

    public String terminal() {
        return terminalId;
    }

    public long creditCents() {
        return payments.stream().filter(p -> p.method().isCustomerAccount()).mapToLong(SalePaymentEntity::amountCents).sum();
    }

    public Set<SaleAnomaly> anomalies() {
        if (anomalies == null || anomalies.isBlank()) {
            return EnumSet.noneOf(SaleAnomaly.class);
        }
        return Arrays.stream(anomalies.split(",")).map(SaleAnomaly::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SaleAnomaly.class)));
    }

    public UUID id() {
        return id;
    }

    public UUID clientSaleId() {
        return clientSaleId;
    }

    public String terminalId() {
        return terminalId;
    }

    public String ticketNumber() {
        return ticketNumber;
    }

    public SaleOrigin origin() {
        return origin;
    }

    public Status status() {
        return status;
    }

    public boolean reviewRequired() {
        return reviewRequired;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public UUID cashierUserId() {
        return cashierUserId;
    }

    public long totalGrossCents() {
        return totalGrossCents;
    }

    public long totalDiscountCents() {
        return totalDiscountCents;
    }

    public long totalNetCents() {
        return totalNetCents;
    }

    public Long ordonnancierNumber() {
        return ordonnancierNumber;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public List<SaleLineEntity> lines() {
        return List.copyOf(lines);
    }

    public List<SalePaymentEntity> payments() {
        return List.copyOf(payments);
    }
}
