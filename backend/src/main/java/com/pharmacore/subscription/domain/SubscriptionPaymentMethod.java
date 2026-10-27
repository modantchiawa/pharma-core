package com.pharmacore.subscription.domain;

/** How a pharmacy paid its subscription to the vendor, outside the application. */
public enum SubscriptionPaymentMethod {
    MOBILE_MONEY,
    VIREMENT,
    ESPECES,
    CHEQUE,
    AUTRE
}
