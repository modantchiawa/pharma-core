package com.pharmacore.sales.domain;

/** Rule violations detected on a sale. Blocking online, flagged for pharmacist review offline. */
public enum SaleAnomaly {
    PRICE_MISMATCH,
    DISCOUNT_ABOVE_LIMIT,
    MISSING_DISPENSING_RIGHT,
    PRESCRIPTION_MISSING,
    PAYMENT_MISMATCH,
    INACTIVE_PRODUCT,
    FUTURE_TIMESTAMP,
    STOCK_SHORTFALL
}
