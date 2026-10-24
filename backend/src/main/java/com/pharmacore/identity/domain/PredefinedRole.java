package com.pharmacore.identity.domain;

import static com.pharmacore.identity.domain.Permission.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * Roles seeded for every new pharmacy. They are regular tenant roles afterwards: the pharmacy
 * owner may change their permissions or discount ceiling, but cannot delete them.
 */
public enum PredefinedRole {

    TITULAIRE("Pharmacien titulaire", new BigDecimal("100"), EnumSet.allOf(Permission.class)),

    ADJOINT("Pharmacien adjoint", new BigDecimal("30"), EnumSet.complementOf(EnumSet.of(
            ROLE_MANAGE, USER_MANAGE, CONFIG_BRANDING_MANAGE, ACCOUNTING_MANAGE, HUB_ADMIN))),

    PREPARATEUR("Préparateur", new BigDecimal("5"), EnumSet.of(
            SALE_CREATE, SALE_VIEW, PRESCRIPTION_DISPENSE, STOCK_VIEW, STOCK_RECEIVE,
            NETWORK_STOCK_QUERY, NETWORK_CHAT, GUARD_VIEW, CASH_SESSION_MANAGE, CUSTOMER_MANAGE,
            STOCK_COUNT, INSURANCE_VIEW)),

    CAISSIER("Caissier / Vendeur", new BigDecimal("2"), EnumSet.of(
            SALE_CREATE, SALE_VIEW, STOCK_VIEW, GUARD_VIEW, CASH_SESSION_MANAGE, CUSTOMER_MANAGE,
            INSURANCE_VIEW)),

    GESTIONNAIRE_STOCK("Gestionnaire de stock", BigDecimal.ZERO, EnumSet.of(
            STOCK_VIEW, STOCK_RECEIVE, STOCK_ADJUST, STOCK_PRICE_MANAGE, NETWORK_STOCK_QUERY,
            NETWORK_TRANSFER_REQUEST, NETWORK_TRANSFER_RESPOND, GUARD_VIEW, REPORT_VIEW,
            PROCUREMENT_VIEW, PROCUREMENT_MANAGE, STOCK_COUNT, STOCK_COUNT_VALIDATE, DASHBOARD_VIEW,
            DATA_IMPORT));

    private final String label;
    private final BigDecimal maxDiscountPercent;
    private final Set<Permission> permissions;

    PredefinedRole(String label, BigDecimal maxDiscountPercent, Set<Permission> permissions) {
        this.label = label;
        this.maxDiscountPercent = maxDiscountPercent;
        this.permissions = Set.copyOf(permissions);
    }

    public String label() {
        return label;
    }

    public BigDecimal maxDiscountPercent() {
        return maxDiscountPercent;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
