package com.pharmacore.control.domain;

import java.util.Optional;

/**
 * When a sensitive operation needs a second person: always for a narcotic, otherwise when its
 * value is strictly above the pharmacy's threshold for that kind of operation (a threshold of 0
 * switches the rule off).
 */
public final class FourEyesPolicy {

    private FourEyesPolicy() {
    }

    /** Operations under four-eyes control. */
    public enum Action {
        /** Lot quantity corrected by hand. */
        STOCK_ADJUSTMENT,
        /** Breakage, expiry declared by hand, loss or theft. */
        STOCK_WRITE_OFF,
        /** Validation of a physical count (its variance is applied to the stock). */
        STOCK_COUNT,
        /** Partial return refunded to the customer. */
        SALE_RETURN,
        /** Cancellation of a sale that was paid. */
        SALE_CANCELLATION
    }

    /** Value thresholds, in minor units of the pharmacy currency. */
    public record Thresholds(long adjustmentCents, long refundCents, long cancellationCents) {

        long of(Action action) {
            return switch (action) {
                case STOCK_ADJUSTMENT, STOCK_WRITE_OFF, STOCK_COUNT -> adjustmentCents;
                case SALE_RETURN -> refundCents;
                case SALE_CANCELLATION -> cancellationCents;
            };
        }
    }

    /** Why a second person must sign, or empty when the requester may act alone. */
    public sealed interface Requirement {

        long threshold();

        record Narcotic(long threshold) implements Requirement {
        }

        record AboveThreshold(long threshold) implements Requirement {
        }
    }

    /** @param amountCents absolute value of the operation (stock at cost, refund, paid amount) */
    public static Optional<Requirement> requirement(Action action, long amountCents, boolean narcotic,
                                                    Thresholds thresholds) {
        long threshold = thresholds.of(action);
        if (narcotic) {
            return Optional.of(new Requirement.Narcotic(threshold));
        }
        if (threshold > 0 && Math.abs(amountCents) > threshold) {
            return Optional.of(new Requirement.AboveThreshold(threshold));
        }
        return Optional.empty();
    }
}
