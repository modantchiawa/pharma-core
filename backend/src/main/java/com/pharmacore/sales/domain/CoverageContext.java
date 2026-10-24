package com.pharmacore.sales.domain;

import java.util.UUID;

/**
 * What the application knows about an insurance policy when a sale declares a third-party
 * payer share on it. Pure data, so the rule in {@link SaleRules} stays framework-free.
 *
 * @param ratePercent           covered share of the sale, 0..100
 * @param remainingCeilingCents what the policy can still cover this month, {@code null} = no ceiling
 * @param valid                 policy active, started and not yet expired at the time of the sale
 * @param customerMatches       the policy belongs to the sale's customer
 */
public record CoverageContext(UUID policyId, int ratePercent, Long remainingCeilingCents, boolean valid,
                              boolean customerMatches) {

    public CoverageContext {
        if (ratePercent < 0 || ratePercent > 100) {
            throw new IllegalArgumentException("Coverage rate must be between 0 and 100");
        }
        if (remainingCeilingCents != null && remainingCeilingCents < 0) {
            throw new IllegalArgumentException("Remaining ceiling cannot be negative");
        }
    }

    public boolean usable() {
        return valid && customerMatches;
    }

    /** Insurer share of {@code netCents}: rate applied, rounded half up, capped by the ceiling. */
    public long expectedShareCents(long netCents) {
        long share = Math.floorDiv(Math.multiplyExact(netCents, (long) ratePercent) + 50, 100);
        return remainingCeilingCents == null ? share : Math.min(share, remainingCeilingCents);
    }
}
