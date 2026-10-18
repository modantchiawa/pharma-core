package com.pharmacore.sales.domain;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** Authorization attributes of the cashier who performed the sale (ABAC subject). */
public record SellerProfile(UUID userId, Set<String> permissions, BigDecimal maxDiscountPercent) {

    public SellerProfile {
        permissions = Set.copyOf(permissions);
    }

    public boolean can(String permission) {
        return permissions.contains(permission);
    }
}
