package com.pharmacore.sales.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.customers.application.CustomerService;
import com.pharmacore.insurance.application.InsuranceCoverageResolver;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.prescription.application.PrescriptionService;
import com.pharmacore.sales.application.certification.CertificationService;
import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.inventory.application.StockLedger;
import com.pharmacore.inventory.domain.AllocationPlan;
import com.pharmacore.inventory.domain.LotAllocation;
import com.pharmacore.inventory.domain.ShortfallPolicy;
import com.pharmacore.inventory.persistence.StockEntity;
import com.pharmacore.inventory.persistence.StockMovementEntity;
import com.pharmacore.inventory.persistence.StockRepository;
import com.pharmacore.sales.domain.CatalogFacts;
import com.pharmacore.sales.domain.SaleAnomaly;
import com.pharmacore.sales.domain.SaleDecision;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOrigin;
import com.pharmacore.sales.domain.SaleOutcome;
import com.pharmacore.sales.domain.SaleRules;
import com.pharmacore.sales.domain.SellerProfile;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleLineEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.sales.persistence.TenantSequenceRepository;
import com.pharmacore.shared.audit.AuditTrail;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one sale atomically: idempotency check, rule evaluation, FEFO stock withdrawal,
 * ordonnancier numbering, persistence and audit, all in a single transaction.
 */
@Service
public class SaleRecordingService {

    private final SaleRepository sales;
    private final ProductRepository products;
    private final StockRepository stocks;
    private final StockLedger ledger;
    private final TenantSequenceRepository sequences;
    private final SellerProfiles sellers;
    private final CustomerService customers;
    private final CashSessionService cashSessions;
    private final AccountingPoster accounting;
    private final CertificationService certification;
    private final InsuranceCoverageResolver coverage;
    private final PrescriptionService prescriptions;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final Clock clock;

    public SaleRecordingService(SaleRepository sales, ProductRepository products, StockRepository stocks,
                                StockLedger ledger, TenantSequenceRepository sequences, SellerProfiles sellers,
                                CustomerService customers, CashSessionService cashSessions,
                                AccountingPoster accounting, CertificationService certification,
                                InsuranceCoverageResolver coverage, PrescriptionService prescriptions,
                                TenantCalendar calendar, AuditTrail audit, Clock clock) {
        this.coverage = coverage;
        this.prescriptions = prescriptions;
        this.sales = sales;
        this.products = products;
        this.stocks = stocks;
        this.ledger = ledger;
        this.sequences = sequences;
        this.sellers = sellers;
        this.customers = customers;
        this.cashSessions = cashSessions;
        this.accounting = accounting;
        this.certification = certification;
        this.calendar = calendar;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public SaleOutcome record(SaleDraft draft) {
        var existing = sales.findIdByClientSaleId(draft.clientSaleId());
        if (existing.isPresent()) {
            return new SaleOutcome.Duplicate(draft.clientSaleId(), existing.get());
        }

        Set<UUID> productIds = draft.lines().stream().map(SaleDraft.Line::productId).collect(Collectors.toSet());
        Map<UUID, ProductEntity> catalog = products.findByIdIn(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::id, Function.identity()));
        if (catalog.size() != productIds.size()) {
            return new SaleOutcome.Rejected(draft.clientSaleId(), "unknown-product", Set.of(),
                    "The sale references products absent from the national catalog");
        }
        Map<UUID, StockEntity> stockByProduct = stocks.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(StockEntity::productId, Function.identity()));
        Map<UUID, CatalogFacts> facts = catalog.values().stream().collect(Collectors.toMap(ProductEntity::id,
                product -> new CatalogFacts(product.id(), referencePrice(product, stockByProduct.get(product.id())),
                        product.listing(), product.active())));

        Instant now = clock.instant();
        LocalDate today = calendar.today();
        SellerProfile seller = sellers.resolve(draft.cashierUserId());
        InsuranceCoverageResolver.Resolution insured = coverage.resolve(draft, today);
        Set<SaleAnomaly> anomalies = EnumSet.noneOf(SaleAnomaly.class);
        anomalies.addAll(SaleRules.evaluate(draft, facts, seller, now, insured.contexts()));
        if (draft.prescriptionId() != null
                && prescriptions.refusalReason(draft.prescriptionId(), draft.customerId(), today).isPresent()) {
            anomalies.add(SaleAnomaly.PRESCRIPTION_RENEWAL_REFUSED);
        }
        if (draft.onCredit() && draft.customerId() != null && !anomalies.contains(SaleAnomaly.CREDIT_NOT_ALLOWED)
                && !customers.addCredit(draft.customerId(), draft.creditCents())) {
            anomalies.add(SaleAnomaly.CREDIT_LIMIT_EXCEEDED);
        }

        return switch (SaleRules.decide(draft.origin(), anomalies)) {
            case SaleDecision.Reject reject -> {
                if (draft.onCredit() && draft.customerId() != null && !reject.violations().contains(SaleAnomaly.CREDIT_NOT_ALLOWED)) {
                    customers.releaseCredit(draft.customerId(), draft.creditCents());
                }
                yield new SaleOutcome.Rejected(draft.clientSaleId(), "sale-rule-violation",
                        reject.violations(), "The sale violates business rules: " + reject.violations());
            }
            case SaleDecision.Accept accept -> persist(draft, catalog, seller, accept.anomalies(), insured, now, today);
        };
    }

    private SaleOutcome persist(SaleDraft draft, Map<UUID, ProductEntity> catalog, SellerProfile seller,
                                Set<SaleAnomaly> ruleAnomalies, InsuranceCoverageResolver.Resolution insured,
                                Instant now, LocalDate today) {
        UUID saleId = UUID.randomUUID();
        Map<UUID, Integer> quantities = draft.lines().stream()
                .collect(Collectors.toMap(SaleDraft.Line::productId, SaleDraft.Line::quantity, Integer::sum,
                        LinkedHashMap::new));
        ShortfallPolicy policy = draft.origin() == SaleOrigin.ONLINE ? ShortfallPolicy.REJECT : ShortfallPolicy.RECORD;
        Map<UUID, AllocationPlan> plans = ledger.withdraw(quantities, policy, StockMovementEntity.Type.SALE,
                new StockLedger.Reference("SALE", saleId, draft.occurredAt(), seller.userId()));

        Set<SaleAnomaly> anomalies = EnumSet.noneOf(SaleAnomaly.class);
        anomalies.addAll(ruleAnomalies);
        if (plans.values().stream().anyMatch(plan -> !plan.complete())) {
            anomalies.add(SaleAnomaly.STOCK_SHORTFALL);
        }

        boolean listed = draft.lines().stream()
                .anyMatch(line -> catalog.get(line.productId()).listing().recordedInOrdonnancier());
        Long ordonnancier = listed ? sequences.next(TenantSequenceRepository.ORDONNANCIER) : null;
        var prescription = draft.prescription();

        UUID cashSession = cashSessions.sessionFor(draft.terminalId(), draft.occurredAt()).orElse(null);
        var sale = new SaleEntity(saleId, draft.clientSaleId(), draft.terminalId(), draft.ticketNumber(), draft.origin(),
                draft.occurredAt(), now, seller.userId(), draft.grossCents(), draft.discountCents(), draft.netCents(),
                prescription == null ? null : prescription.prescriberName(),
                prescription == null ? null : prescription.prescriberRpps(),
                prescription == null ? null : prescription.patientReference(),
                ordonnancier, anomalies, draft.customerId(), cashSession, certification.required());

        var remaining = new LotCursor(plans);
        short lineNo = 1;
        for (SaleDraft.Line line : draft.lines()) {
            sale.addLine(new SaleLineEntity(lineNo++, line.productId(), line.quantity(), line.unitPriceCents(),
                    line.discountCents(), catalog.get(line.productId()).vatRate(),
                    remaining.take(line.productId(), line.quantity())));
        }
        draft.payments().forEach(payment -> sale.addPayment(new SalePaymentEntity(payment.method(),
                payment.amountCents(), payment.thirdPartyCode(), payment.reference(), payment.provider(),
                payment.method().isCustomerAccount() ? draft.customerId() : null,
                payment.policyId() == null ? null : insured.insurerByPolicy().get(payment.policyId()),
                payment.policyId())));
        // save() merges an entity with an assigned id: later changes must go to the managed copy
        SaleEntity managed = sales.save(sale);
        // A refused renewal keeps the (offline) sale for review but does not consume the prescription
        if (draft.prescriptionId() != null && !anomalies.contains(SaleAnomaly.PRESCRIPTION_RENEWAL_REFUSED)) {
            prescriptions.recordDispensing(draft.prescriptionId(), managed, today);
        }
        accounting.post(Postings.sale(sale, today, insured.accountByPolicy()), "SALE", sale.id());

        audit.record("SALE_RECORDED", "SALE", sale.id(), Map.of(
                "clientSaleId", draft.clientSaleId().toString(),
                "origin", draft.origin().name(),
                "terminal", draft.terminalId(),
                "ticket", draft.ticketNumber(),
                "netCents", Long.toString(draft.netCents()),
                "anomalies", anomalies.stream().map(Enum::name).sorted().collect(Collectors.joining(",")),
                "ordonnancier", String.valueOf(ordonnancier)));
        return new SaleOutcome.Accepted(draft.clientSaleId(), sale.id(), ordonnancier, anomalies);
    }

    private static long referencePrice(ProductEntity product, StockEntity stock) {
        if (stock != null && stock.sellingPriceCents() != null) {
            return stock.sellingPriceCents();
        }
        return product.publicPriceCents() == null ? 0L : product.publicPriceCents();
    }

    /**
     * Distributes the per-product allocation plan over the sale lines (a product may appear on
     * several lines, e.g. one with and one without discount).
     */
    private static final class LotCursor {

        private final Map<UUID, Deque<LotAllocation>> pending;

        LotCursor(Map<UUID, AllocationPlan> plans) {
            this.pending = plans.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                    entry -> new ArrayDeque<>(entry.getValue().allocations())));
        }

        List<LotAllocation> take(UUID productId, int quantity) {
            var queue = pending.get(productId);
            var taken = new java.util.ArrayList<LotAllocation>();
            int remaining = quantity;
            while (remaining > 0 && !queue.isEmpty()) {
                var head = queue.poll();
                int used = Math.min(head.quantity(), remaining);
                taken.add(new LotAllocation(head.lotId(), used));
                if (head.quantity() > used) {
                    queue.addFirst(new LotAllocation(head.lotId(), head.quantity() - used));
                }
                remaining -= used;
            }
            return taken;
        }
    }
}
