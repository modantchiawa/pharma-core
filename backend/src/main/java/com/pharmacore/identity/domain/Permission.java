package com.pharmacore.identity.domain;

/** Fine-grained permissions. Codes are mirrored in the {@code permission} table (V3 migration). */
public enum Permission {
    SALE_CREATE,
    SALE_VIEW,
    SALE_CANCEL,
    SALE_DISCOUNT_OVERRIDE,
    SALE_PRICE_OVERRIDE,
    SALE_REVIEW,
    PRESCRIPTION_DISPENSE,
    NARCOTIC_DISPENSE,
    STOCK_VIEW,
    STOCK_RECEIVE,
    STOCK_ADJUST,
    STOCK_PRICE_MANAGE,
    NETWORK_STOCK_QUERY,
    NETWORK_TRANSFER_REQUEST,
    NETWORK_TRANSFER_RESPOND,
    NETWORK_CHAT,
    GUARD_VIEW,
    GUARD_MANAGE,
    CONFIG_BRANDING_MANAGE,
    USER_MANAGE,
    ROLE_MANAGE,
    AUDIT_VIEW,
    REPORT_VIEW
}
