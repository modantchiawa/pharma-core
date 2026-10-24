package com.pharmacore.sales.domain;

import com.pharmacore.identity.domain.DiscountPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure business rules of a counter sale (no framework dependency). The same evaluation is used
 * live and on offline replay; only the consequence differs (see {@link #decide}).
 */
public final class SaleRules {

    /** Tolerated terminal clock drift before a timestamp is considered in the future. */
    static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

    private SaleRules() {
    }

    public static Set<SaleAnomaly> evaluate(SaleDraft draft, Map<UUID, CatalogFacts> catalog, SellerProfile seller,
                                            Instant now) {
        return evaluate(draft, catalog, seller, now, Map.of());
    }

    /**
     * @param coverages the policies referenced by third-party payer shares, as resolved by the
     *                  application; a referenced policy absent from the map is invalid
     */
    public static Set<SaleAnomaly> evaluate(SaleDraft draft, Map<UUID, CatalogFacts> catalog, SellerProfile seller,
                                            Instant now, Map<UUID, CoverageContext> coverages) {
        Set<SaleAnomaly> anomalies = EnumSet.noneOf(SaleAnomaly.class);
        boolean prescriptionNeeded = false;
        for (SaleDraft.Line line : draft.lines()) {
            CatalogFacts facts = catalog.get(line.productId());
            if (!facts.active()) {
                anomalies.add(SaleAnomaly.INACTIVE_PRODUCT);
            }
            if (line.unitPriceCents() != facts.referencePriceCents() && !seller.can("SALE_PRICE_OVERRIDE")) {
                anomalies.add(SaleAnomaly.PRICE_MISMATCH);
            }
            if (!DiscountPolicy.isAllowed(line.grossCents(), line.discountCents(), seller.maxDiscountPercent(),
                    seller.can("SALE_DISCOUNT_OVERRIDE"))) {
                anomalies.add(SaleAnomaly.DISCOUNT_ABOVE_LIMIT);
            }
            if (facts.listing().requiredPermission().filter(permission -> !seller.can(permission)).isPresent()) {
                anomalies.add(SaleAnomaly.MISSING_DISPENSING_RIGHT);
            }
            prescriptionNeeded |= facts.listing().requiresPrescription();
        }
        if (prescriptionNeeded && (draft.prescription() == null || isBlank(draft.prescription().prescriberName()))) {
            anomalies.add(SaleAnomaly.PRESCRIPTION_MISSING);
        }
        if (draft.paidCents() != draft.netCents()) {
            anomalies.add(SaleAnomaly.PAYMENT_MISMATCH);
        }
        if (draft.onCredit() && (draft.customerId() == null || !seller.can("CREDIT_SALE"))) {
            anomalies.add(SaleAnomaly.CREDIT_NOT_ALLOWED);
        }
        anomalies.addAll(evaluateCoverage(draft, coverages));
        if (draft.occurredAt().isAfter(now.plus(CLOCK_TOLERANCE))) {
            anomalies.add(SaleAnomaly.FUTURE_TIMESTAMP);
        }
        return anomalies;
    }

    /**
     * Online, any violation is refused before anything is written. Offline, the goods and the
     * money have already changed hands: the sale is kept (zero data loss) and routed to the
     * pharmacist's review queue.
     */
    public static SaleDecision decide(SaleOrigin origin, Set<SaleAnomaly> anomalies) {
        return switch (origin) {
            case ONLINE -> anomalies.isEmpty()
                    ? new SaleDecision.Accept(anomalies)
                    : new SaleDecision.Reject(anomalies);
            case OFFLINE_REPLAY -> new SaleDecision.Accept(anomalies);
        };
    }

    /**
     * Each insured share must be exactly what its policy covers on the sale total (rate, then
     * monthly ceiling). The cashier never types that amount: the terminal computes it from the
     * same rule, so a mismatch means a stale policy or a tampered request.
     */
    static Set<SaleAnomaly> evaluateCoverage(SaleDraft draft, Map<UUID, CoverageContext> coverages) {
        Set<SaleAnomaly> anomalies = EnumSet.noneOf(SaleAnomaly.class);
        draft.insuredSharesByPolicy().forEach((policyId, declared) -> {
            CoverageContext coverage = coverages.get(policyId);
            if (coverage == null || !coverage.usable()) {
                anomalies.add(SaleAnomaly.INSURANCE_POLICY_INVALID);
            } else if (coverage.expectedShareCents(draft.netCents()) != declared) {
                anomalies.add(SaleAnomaly.INSURANCE_SHARE_MISMATCH);
            }
        });
        return anomalies;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
