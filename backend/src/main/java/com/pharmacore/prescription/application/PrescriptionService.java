package com.pharmacore.prescription.application;

import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.customers.persistence.CustomerEntity;
import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.prescription.persistence.PrescriptionEntity;
import com.pharmacore.prescription.persistence.PrescriptionLineEntity;
import com.pharmacore.prescription.persistence.PrescriptionRepository;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Renewable prescriptions on file and the dispensings counted against them. */
@Service
public class PrescriptionService {

    /** A chronic treatment is dispensed for a month: a renewal is due this long after the last one. */
    static final int RENEWAL_INTERVAL_DAYS = 28;

    private final PrescriptionRepository prescriptions;
    private final SaleRepository sales;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PrescriptionService(PrescriptionRepository prescriptions, SaleRepository sales, ProductRepository products,
                               CustomerRepository customers, TenantCalendar calendar, AuditTrail audit, JdbcClient jdbc,
                               Clock clock) {
        this.prescriptions = prescriptions;
        this.sales = sales;
        this.products = products;
        this.customers = customers;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record LineView(short lineNo, UUID productId, String cip13, String designation, String listing, int quantity,
                           String posology) {
    }

    public record PrescriptionView(UUID id, UUID customerId, String customerName, String prescriberName,
                                   String prescriberIdentifier, String patientReference, LocalDate issuedOn,
                                   LocalDate validUntil, int renewalsAllowed, int renewalsUsed, int renewalsRemaining,
                                   PrescriptionEntity.Status status, String notes, Instant lastDispensedAt,
                                   LocalDate nextRenewalDue, Instant createdAt, List<LineView> lines) {
    }

    public record LineCommand(UUID productId, int quantity, String posology) {
    }

    @Transactional
    public PrescriptionView create(PrescriptionEntity.Details details, List<LineCommand> lines) {
        if (lines.isEmpty()) {
            throw new BusinessRuleException("empty-prescription", "Une ordonnance comporte au moins un produit");
        }
        var user = TenantContext.requireTenantUser();
        if (details.customerId() != null) {
            customers.findById(details.customerId())
                    .orElseThrow(() -> new NotFoundException("Customer", details.customerId()));
        }
        Map<UUID, ProductEntity> catalog = catalog(lines.stream().map(LineCommand::productId).toList());
        if (catalog.size() != lines.stream().map(LineCommand::productId).distinct().count()) {
            throw new BusinessRuleException("unknown-product", "Un produit de l'ordonnance est inconnu du catalogue");
        }
        PrescriptionEntity prescription;
        try {
            prescription = new PrescriptionEntity(details, user.userId(), clock.instant());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("invalid-prescription", e.getMessage());
        }
        short lineNo = 1;
        for (LineCommand line : lines) {
            if (line.quantity() <= 0) {
                throw new BusinessRuleException("invalid-quantity", "Quantité prescrite invalide");
            }
            prescription.addLine(new PrescriptionLineEntity(lineNo++, line.productId(), line.quantity(), line.posology()));
        }
        prescriptions.save(prescription);
        audit.record("PRESCRIPTION_FILED", "PRESCRIPTION", prescription.id(), Map.of(
                "prescriber", details.prescriberName(), "customer", String.valueOf(details.customerId()),
                "dispensings", Integer.toString(details.renewalsAllowed()), "validUntil", details.validUntil().toString()));
        return view(prescription, catalog, customerNames(List.of(prescription)), Map.of());
    }

    @Transactional(readOnly = true)
    public PrescriptionView get(UUID id) {
        var prescription = prescriptions.findWithLines(id).orElseThrow(() -> new NotFoundException("Prescription", id));
        return view(prescription, catalog(productIds(List.of(prescription))), customerNames(List.of(prescription)),
                lastDispensings(List.of(id)));
    }

    @Transactional(readOnly = true)
    public List<PrescriptionView> search(UUID customerId, String term, PrescriptionEntity.Status status, int limit) {
        String needle = term == null || term.isBlank() ? null : "%" + term.trim() + "%";
        List<UUID> ids = jdbc.sql("""
                        SELECT id FROM prescription
                        WHERE tenant_id = :tenant
                          AND (:customer::uuid IS NULL OR customer_id = :customer::uuid)
                          AND (:status::text IS NULL OR status = :status::text)
                          AND (:term::text IS NULL OR prescriber_name ILIKE :term::text
                               OR coalesce(patient_reference, '') ILIKE :term::text)
                        ORDER BY created_at DESC
                        LIMIT :limit""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("customer", customerId)
                .param("status", status == null ? null : status.name())
                .param("term", needle)
                .param("limit", Math.clamp(limit, 1, 200))
                .query(UUID.class)
                .list();
        return views(inOrder(ids));
    }

    private List<PrescriptionEntity> inOrder(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, PrescriptionEntity> byId = prescriptions.findAllWithLines(ids).stream()
                .collect(Collectors.toMap(PrescriptionEntity::id, Function.identity()));
        return ids.stream().map(byId::get).filter(p -> p != null).toList();
    }

    /**
     * Prescriptions worth a call to the patient: dispensings remain and the last one is at least
     * {@value #RENEWAL_INTERVAL_DAYS} days old, or the validity ends within {@code withinDays}.
     */
    @Transactional(readOnly = true)
    public List<PrescriptionView> renewalsDue(int withinDays) {
        LocalDate today = calendar.today();
        List<UUID> ids = jdbc.sql("""
                        SELECT p.id
                        FROM prescription p
                        LEFT JOIN LATERAL (SELECT max(s.occurred_at) AS last_at FROM sale s
                                           WHERE s.prescription_id = p.id AND s.status = 'COMPLETED') d ON true
                        WHERE p.tenant_id = :tenant AND p.status = 'ACTIVE' AND p.valid_until >= :today
                          AND (coalesce(d.last_at, p.issued_on::timestamptz) <= :cutoff
                               OR p.valid_until <= :horizon)
                        ORDER BY coalesce(d.last_at, p.issued_on::timestamptz)""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("today", Date.valueOf(today))
                .param("cutoff", Timestamp.from(clock.instant().minus(RENEWAL_INTERVAL_DAYS, ChronoUnit.DAYS)))
                .param("horizon", Date.valueOf(today.plusDays(Math.max(withinDays, 0))))
                .query(UUID.class)
                .list();
        return views(inOrder(ids));
    }

    /** Counter action: the sale already recorded is counted as a dispensing of the prescription. */
    @Transactional
    public PrescriptionView dispense(UUID prescriptionId, UUID saleId) {
        var sale = sales.findById(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
        if (sale.status() != SaleEntity.Status.COMPLETED) {
            throw new BusinessRuleException("sale-cancelled", "Une vente annulée ne compte pas comme délivrance");
        }
        if (sale.prescriptionId() != null) {
            throw new BusinessRuleException("sale-already-attached", "Cette vente est déjà rattachée à une ordonnance");
        }
        var prescription = prescriptions.findWithLines(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Prescription", prescriptionId));
        if (prescription.customerId() != null && sale.customerId() != null
                && !prescription.customerId().equals(sale.customerId())) {
            throw new BusinessRuleException("customer-mismatch", "L'ordonnance appartient à un autre client");
        }
        LocalDate today = calendar.today();
        try {
            prescription.dispense(today);
        } catch (IllegalStateException e) {
            throw new BusinessRuleException("prescription-refused", e.getMessage());
        }
        sale.attachPrescription(prescriptionId);
        audit.record("PRESCRIPTION_DISPENSED", "PRESCRIPTION", prescriptionId, Map.of(
                "sale", saleId.toString(), "ticket", sale.ticketNumber(),
                "used", prescription.renewalsUsed() + "/" + prescription.renewalsAllowed(),
                "status", prescription.status().name()));
        return get(prescriptionId);
    }

    /**
     * Called while a sale is being recorded, before anything is written: the reason the
     * prescription cannot be used, if any (unknown, other customer, exhausted, expired, cancelled).
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<String> refusalReason(UUID prescriptionId, UUID customerId, LocalDate today) {
        return prescriptions.findById(prescriptionId)
                .map(prescription -> {
                    if (prescription.customerId() != null && customerId != null
                            && !prescription.customerId().equals(customerId)) {
                        return Optional.of("L'ordonnance appartient à un autre client");
                    }
                    return prescription.refusalReason(today);
                })
                .orElse(Optional.of("Ordonnance inconnue"));
    }

    /** Called by sale recording once the sale row exists; the caller has checked {@link #refusalReason}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDispensing(UUID prescriptionId, SaleEntity sale, LocalDate today) {
        var prescription = prescriptions.findById(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Prescription", prescriptionId));
        prescription.dispense(today);
        sale.attachPrescription(prescriptionId);
        audit.record("PRESCRIPTION_DISPENSED", "PRESCRIPTION", prescriptionId, Map.of(
                "sale", sale.id().toString(), "ticket", sale.ticketNumber(),
                "used", prescription.renewalsUsed() + "/" + prescription.renewalsAllowed(),
                "status", prescription.status().name()));
    }

    @Transactional
    public PrescriptionView cancel(UUID prescriptionId, String reason) {
        var prescription = prescriptions.findWithLines(prescriptionId)
                .orElseThrow(() -> new NotFoundException("Prescription", prescriptionId));
        try {
            prescription.cancel();
        } catch (IllegalStateException e) {
            throw new BusinessRuleException("already-cancelled", e.getMessage());
        }
        audit.record("PRESCRIPTION_CANCELLED", "PRESCRIPTION", prescriptionId, Map.of("reason", reason));
        return get(prescriptionId);
    }

    // ------------------------------------------------------------------ views

    private List<PrescriptionView> views(List<PrescriptionEntity> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        LocalDate today = calendar.today();
        found.forEach(p -> p.expireIfDue(today));
        Map<UUID, ProductEntity> catalog = catalog(productIds(found));
        Map<UUID, String> names = customerNames(found);
        Map<UUID, Instant> last = lastDispensings(found.stream().map(PrescriptionEntity::id).toList());
        return found.stream().map(p -> view(p, catalog, names, last)).toList();
    }

    private PrescriptionView view(PrescriptionEntity p, Map<UUID, ProductEntity> catalog, Map<UUID, String> names,
                                  Map<UUID, Instant> lastDispensings) {
        Instant lastAt = lastDispensings.get(p.id());
        int remaining = Math.max(p.renewalsAllowed() - p.renewalsUsed(), 0);
        LocalDate nextDue = null;
        if (p.status() == PrescriptionEntity.Status.ACTIVE && remaining > 0) {
            nextDue = lastAt == null ? p.issuedOn().plusDays(RENEWAL_INTERVAL_DAYS)
                    : LocalDate.ofInstant(lastAt, clock.getZone()).plusDays(RENEWAL_INTERVAL_DAYS);
            if (nextDue.isAfter(p.validUntil())) {
                nextDue = p.validUntil();
            }
        }
        return new PrescriptionView(p.id(), p.customerId(),
                p.customerId() == null ? null : names.get(p.customerId()), p.prescriberName(),
                p.prescriberIdentifier(), p.patientReference(), p.issuedOn(), p.validUntil(), p.renewalsAllowed(),
                p.renewalsUsed(), remaining, p.status(), p.notes(), lastAt, nextDue, p.createdAt(),
                p.lines().stream().map(line -> {
                    ProductEntity product = catalog.get(line.productId());
                    return new LineView(line.lineNo(), line.productId(),
                            product == null ? null : product.cip13(), product == null ? null : product.designation(),
                            product == null ? null : product.listing().name(), line.quantity(), line.posology());
                }).toList());
    }

    private Map<UUID, ProductEntity> catalog(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return products.findByIdIn(ids).stream().collect(Collectors.toMap(ProductEntity::id, Function.identity()));
    }

    private static List<UUID> productIds(List<PrescriptionEntity> found) {
        return found.stream().flatMap(p -> p.lines().stream()).map(PrescriptionLineEntity::productId).distinct().toList();
    }

    private Map<UUID, String> customerNames(List<PrescriptionEntity> found) {
        List<UUID> ids = found.stream().map(PrescriptionEntity::customerId).filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return customers.findAllById(ids).stream()
                .collect(Collectors.toMap(CustomerEntity::id, CustomerEntity::displayName));
    }

    private Map<UUID, Instant> lastDispensings(List<UUID> prescriptionIds) {
        var result = new HashMap<UUID, Instant>();
        jdbc.sql("""
                        SELECT prescription_id, max(occurred_at) AS last_at FROM sale
                        WHERE prescription_id IN (:ids) AND status = 'COMPLETED'
                        GROUP BY prescription_id""")
                .param("ids", prescriptionIds)
                .query((rs, rowNum) -> result.put(rs.getObject("prescription_id", UUID.class),
                        rs.getTimestamp("last_at").toInstant()))
                .list();
        return result;
    }
}
