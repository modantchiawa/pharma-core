package com.pharmacore.sales.domain;

public enum PaymentMethod {
    CASH,
    CARD,
    CHECK,
    /** Tiers-payant: amount covered by an insurer, mutual or company agreement. */
    THIRD_PARTY_PAYER,
    VOUCHER,
    /** Airtel Money, Moov Money, Orange Money, Zamani Cash, MyNITA, Wave... ({@code provider}). */
    MOBILE_MONEY,
    /** Sale on account ("ardoise"): the customer's credit balance increases. */
    CREDIT;

    public boolean isCustomerAccount() {
        return this == CREDIT;
    }
}
