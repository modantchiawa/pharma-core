package com.pharmacore.control.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Risk score of a product, from 0 to 100, as the sum of six capped components. Each component
 * is proportional to its factor up to a cap, so the score reads like a checklist:
 * <table>
 *   <caption>Components</caption>
 *   <tr><th>Component</th><th>Points</th><th>Full points at</th></tr>
 *   <tr><td>Value on hand at cost</td><td>20</td><td>{@value #VALUE_CAP_CENTS} minor units (100 000 F CFA)</td></tr>
 *   <tr><td>Custody: narcotic / stored in the safe</td><td>25 / 15</td><td>listed or stored</td></tr>
 *   <tr><td>Past count discrepancies (180 days)</td><td>15</td><td>2 discrepancies (8 points each)</td></tr>
 *   <tr><td>Sales velocity (28 days)</td><td>10</td><td>{@value #VELOCITY_CAP} units a day</td></tr>
 *   <tr><td>Days since last counted</td><td>15</td><td>{@value #STALENESS_CAP_DAYS} days, or never counted</td></tr>
 *   <tr><td>Manual adjustments and write-offs (30 days)</td><td>15</td><td>3 adjustments (5 points each)</td></tr>
 * </table>
 * Fast movers, high-value and narcotic products, products already found short and products
 * nobody looked at for a long time come up most often; any product can still be drawn.
 */
public final class RiskScore {

    static final int VALUE_POINTS = 20;
    static final long VALUE_CAP_CENTS = 100_000;
    static final int NARCOTIC_POINTS = 25;
    static final int SAFE_POINTS = 15;
    static final int DISCREPANCY_POINTS_EACH = 8;
    static final int DISCREPANCY_POINTS = 15;
    static final int VELOCITY_POINTS = 10;
    static final double VELOCITY_CAP = 5.0;
    static final int STALENESS_POINTS = 15;
    static final int STALENESS_CAP_DAYS = 60;
    static final int ADJUSTMENT_POINTS_EACH = 5;
    static final int ADJUSTMENT_POINTS = 15;

    private RiskScore() {
    }

    /** Points per component; {@link #total()} is the score. */
    public record Breakdown(int value, int custody, int discrepancies, int velocity, int staleness,
                            int adjustments) {

        public int total() {
            return value + custody + discrepancies + velocity + staleness + adjustments;
        }
    }

    public static Breakdown of(RiskFactors f) {
        int value = (int) Math.round(VALUE_POINTS * Math.min(1.0, Math.max(0, f.valueAtCostCents())
                / (double) VALUE_CAP_CENTS));
        int custody = f.narcotic() ? NARCOTIC_POINTS : f.inSafe() ? SAFE_POINTS : 0;
        int discrepancies = Math.min(DISCREPANCY_POINTS, DISCREPANCY_POINTS_EACH * Math.max(0, f.pastDiscrepancies()));
        int velocity = (int) Math.round(VELOCITY_POINTS * Math.min(1.0, Math.max(0, f.dailySales()) / VELOCITY_CAP));
        int staleness = f.daysSinceCounted() == null ? STALENESS_POINTS
                : (int) Math.round(STALENESS_POINTS * Math.min(1.0, Math.max(0, f.daysSinceCounted())
                        / (double) STALENESS_CAP_DAYS));
        int adjustments = Math.min(ADJUSTMENT_POINTS, ADJUSTMENT_POINTS_EACH * Math.max(0, f.recentAdjustments()));
        return new Breakdown(value, custody, discrepancies, velocity, staleness, adjustments);
    }

    /** Why the product carries its score, strongest reasons first (shown to the pharmacist). */
    public static List<String> reasons(RiskFactors f) {
        Breakdown b = of(f);
        List<String> reasons = new ArrayList<>();
        if (f.narcotic()) {
            reasons.add("stupéfiant");
        } else if (f.inSafe()) {
            reasons.add("rangé au coffre");
        }
        if (b.discrepancies() > 0) {
            reasons.add(f.pastDiscrepancies() + " écart(s) d'inventaire passé(s)");
        }
        if (b.value() >= VALUE_POINTS / 2) {
            reasons.add("valeur élevée au coût");
        }
        if (b.adjustments() > 0) {
            reasons.add(f.recentAdjustments() + " sortie(s) ou ajustement(s) manuel(s) récent(s)");
        }
        if (f.daysSinceCounted() == null) {
            reasons.add("jamais compté");
        } else if (b.staleness() >= STALENESS_POINTS / 2) {
            reasons.add("non compté depuis " + f.daysSinceCounted() + " j");
        }
        if (b.velocity() >= VELOCITY_POINTS / 2) {
            reasons.add(String.format(Locale.FRANCE, "forte rotation (%.1f/j)", f.dailySales()));
        }
        return reasons;
    }
}
