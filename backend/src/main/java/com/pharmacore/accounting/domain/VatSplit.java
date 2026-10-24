package com.pharmacore.accounting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Splits a tax-inclusive amount into its net and VAT parts (VAT rounded, net takes the remainder). */
public record VatSplit(long netCents, long vatCents) {

    public static VatSplit ofInclusive(long inclusiveCents, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() == 0 || inclusiveCents == 0) {
            return new VatSplit(inclusiveCents, 0);
        }
        BigDecimal divisor = BigDecimal.ONE.add(ratePercent.movePointLeft(2));
        long net = BigDecimal.valueOf(inclusiveCents).divide(divisor, 0, RoundingMode.HALF_UP).longValueExact();
        return new VatSplit(net, inclusiveCents - net);
    }

    public static VatSplit ofExclusive(long exclusiveCents, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() == 0) {
            return new VatSplit(exclusiveCents, 0);
        }
        long vat = BigDecimal.valueOf(exclusiveCents).multiply(ratePercent.movePointLeft(2))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new VatSplit(exclusiveCents, vat);
    }
}
