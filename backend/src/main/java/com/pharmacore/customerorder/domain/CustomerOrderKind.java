package com.pharmacore.customerorder.domain;

/** What the pharmacy owes the customer. */
public enum CustomerOrderKind {
    /** "Bon": sold and paid at the counter, the product was out of stock and is handed over on arrival. */
    VOUCHER,
    /** Special order: a product the pharmacy does not keep, ordered for the customer, paid when collected. */
    SPECIAL
}
