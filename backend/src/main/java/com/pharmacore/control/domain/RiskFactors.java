package com.pharmacore.control.domain;

import java.util.UUID;

/**
 * What the pharmacy knows about one product when it draws the "contrôle du jour".
 *
 * @param valueAtCostCents   units on hand × unit cost of their lots
 * @param narcotic           listed as a narcotic (stupéfiant)
 * @param inSafe             stored in a SAFE location
 * @param pastDiscrepancies  validated counts of this product that found a variance (last 180 days)
 * @param dailySales         average units sold per day (last 28 days)
 * @param daysSinceCounted   days since the product was last counted; {@code null} when never counted
 * @param recentAdjustments  manual adjustments and declared write-offs (last 30 days)
 */
public record RiskFactors(UUID productId, long valueAtCostCents, boolean narcotic, boolean inSafe,
                          int pastDiscrepancies, double dailySales, Integer daysSinceCounted,
                          int recentAdjustments) {
}
