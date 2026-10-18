package com.pharmacore.identity.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ABAC rule: the discount granted on a line may not exceed the ceiling carried by the user's
 * roles unless the user holds {@link Permission#SALE_DISCOUNT_OVERRIDE}.
 */
public final class DiscountPolicy {

    private DiscountPolicy() {
    }

    public static BigDecimal discountPercent(long grossCents, long discountCents) {
        if (grossCents <= 0 || discountCents <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(discountCents)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(grossCents), 2, RoundingMode.HALF_UP);
    }

    public static boolean isAllowed(long grossCents, long discountCents, BigDecimal maxDiscountPercent,
                                    boolean hasOverride) {
        return hasOverride || discountPercent(grossCents, discountCents).compareTo(maxDiscountPercent) <= 0;
    }
}
