package com.pharmacore.procurement.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Next-day order proposal, product by product.
 * <p>
 * Target stock = daily demand × (lead time + coverage days + safety days). Daily demand is the
 * recent sales velocity, with lost sales (offline shortfalls) added back since they were real
 * demand. The proposal covers the gap between the target and what is already available or on
 * order, replaces units about to expire, honours the pharmacy's own minimum, and rounds up to
 * the supplier's pack size. Pure function: no I/O, fully unit-tested.
 */
public final class OrderProposalCalculator {

    private OrderProposalCalculator() {
    }

    /**
     * @param soldLastPeriod   units sold over {@code periodDays}
     * @param shortfallUnits   units sold without stock over the same period (lost or deferred sales)
     * @param available        sellable units on the shelf now (not expired, not reserved)
     * @param onOrder          units already ordered, not yet received
     * @param expiringSoon     units on the shelf that expire before the next replenishment can sell them
     * @param reorderThreshold pharmacist's minimum stock (0 = none)
     * @param reorderQuantity  pharmacist's usual order quantity when below threshold (0 = computed)
     * @param seasonalFactor   how the coming weeks sold last year against the yearly average
     *                         (1 = no seasonality known); bounded to {@link #MIN_SEASONAL_FACTOR}
     *                         and {@link #MAX_SEASONAL_FACTOR}
     */
    public record Input(long soldLastPeriod, long shortfallUnits, int periodDays, int available, int onOrder,
                        int expiringSoon, int reorderThreshold, int reorderQuantity, int leadTimeDays,
                        int coverageDays, int safetyDays, int packSize, double seasonalFactor) {

        public Input(long soldLastPeriod, long shortfallUnits, int periodDays, int available, int onOrder,
                     int expiringSoon, int reorderThreshold, int reorderQuantity, int leadTimeDays, int coverageDays,
                     int safetyDays, int packSize) {
            this(soldLastPeriod, shortfallUnits, periodDays, available, onOrder, expiringSoon, reorderThreshold,
                    reorderQuantity, leadTimeDays, coverageDays, safetyDays, packSize, 1.0);
        }

        public Input {
            if (periodDays <= 0 || packSize <= 0) {
                throw new IllegalArgumentException("periodDays and packSize must be positive");
            }
            seasonalFactor = Double.isFinite(seasonalFactor) && seasonalFactor > 0
                    ? Math.clamp(seasonalFactor, MIN_SEASONAL_FACTOR, MAX_SEASONAL_FACTOR)
                    : 1.0;
        }
    }

    /** Seasonality never cuts the demand by more than half nor multiplies it by more than three. */
    public static final double MIN_SEASONAL_FACTOR = 0.5;
    public static final double MAX_SEASONAL_FACTOR = 3.0;
    /** From this factor on, the sheet says why the quantity is higher than the recent sales suggest. */
    public static final double SEASONAL_UPTURN_FROM = 1.25;

    public enum Reason {
        BELOW_TARGET,
        BELOW_THRESHOLD,
        EXPIRING_REPLACEMENT,
        LOST_SALES,
        /** Last year, the coming weeks sold markedly more than the yearly average (malaria season, Ramadan…). */
        SEASONAL_UPTURN,
        /**
         * Added to the sheet by the pharmacist (exceptional need, bulk purchase, special order);
         * never produced by the calculator.
         */
        MANUAL
    }

    public record Proposal(int quantity, double dailyDemand, int targetStock, int projectedStock, List<Reason> reasons) {

        public static final Proposal NONE = new Proposal(0, 0, 0, 0, List.of());

        public boolean needed() {
            return quantity > 0;
        }
    }

    public static Proposal propose(Input in) {
        double dailyDemand = (in.soldLastPeriod() + in.shortfallUnits()) / (double) in.periodDays() * in.seasonalFactor();
        int horizon = in.leadTimeDays() + in.coverageDays() + in.safetyDays();
        int target = (int) Math.ceil(dailyDemand * horizon);
        int projected = in.available() - in.expiringSoon() + in.onOrder();
        List<Reason> reasons = new ArrayList<>();

        int needed = 0;
        if (projected < target) {
            needed = target - projected;
            reasons.add(Reason.BELOW_TARGET);
        }
        if (in.reorderThreshold() > 0 && projected <= in.reorderThreshold()) {
            int minimum = in.reorderQuantity() > 0
                    ? in.reorderQuantity()
                    : Math.max(in.reorderThreshold() - projected, 0);
            if (minimum > needed) {
                needed = minimum;
            }
            reasons.add(Reason.BELOW_THRESHOLD);
        }
        if (in.expiringSoon() > 0 && needed > 0) {
            reasons.add(Reason.EXPIRING_REPLACEMENT);
        }
        if (in.shortfallUnits() > 0 && needed > 0) {
            reasons.add(Reason.LOST_SALES);
        }
        if (in.seasonalFactor() >= SEASONAL_UPTURN_FROM && needed > 0) {
            reasons.add(Reason.SEASONAL_UPTURN);
        }
        if (needed <= 0) {
            return new Proposal(0, dailyDemand, target, projected, List.of());
        }
        int packs = (int) Math.ceil(needed / (double) in.packSize());
        return new Proposal(packs * in.packSize(), dailyDemand, target, projected, List.copyOf(reasons));
    }
}
