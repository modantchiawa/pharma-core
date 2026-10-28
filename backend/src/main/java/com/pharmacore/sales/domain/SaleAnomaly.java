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
    STOCK_SHORTFALL,
    CREDIT_NOT_ALLOWED,
    CREDIT_LIMIT_EXCEEDED,
    /** Third-party payer share differs from what the policy covers on this sale. */
    INSURANCE_SHARE_MISMATCH,
    /** Policy unknown, inactive, expired or belonging to another customer. */
    INSURANCE_POLICY_INVALID,
    /** The renewable prescription attached to the sale is exhausted, expired or cancelled. */
    PRESCRIPTION_RENEWAL_REFUSED,
    /** A mobile money or card transaction reference was already used by another sale. */
    DUPLICATE_PAYMENT_REFERENCE,
    /** A contraindication (interaction, allergy, patient situation) without the pharmacist's override. */
    SAFETY_CONTRAINDICATION,
    /** The seller overrode a contraindication without the right to do so. */
    SAFETY_OVERRIDE_NOT_ALLOWED
}
