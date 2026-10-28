package com.pharmacore.sales.domain;

import java.util.Optional;

/**
 * What a confirmation message says, once read: the operator, the amount, the operator's
 * transaction reference, the payer's number, and whether money came in (a payment) or went out
 * (a transfer the pharmacy made, a balance notice, an advertisement).
 */
public record PaymentNotice(String provider, Long amountCents, String reference, String payerPhone, boolean incoming) {

    public static final PaymentNotice UNREADABLE = new PaymentNotice(null, null, null, null, false);

    public boolean usable() {
        return incoming && amountCents != null && amountCents > 0;
    }

    public Optional<String> referenceOrEmpty() {
        return Optional.ofNullable(reference == null || reference.isBlank() ? null : reference.trim());
    }
}
