package com.pharmacore.sales.domain;

public enum PaymentMethod {
    CASH,
    CARD,
    CHECK,
    /** Tiers-payant: amount covered by the mandatory (AMO) or complementary (AMC) insurer. */
    THIRD_PARTY_PAYER,
    VOUCHER
}
