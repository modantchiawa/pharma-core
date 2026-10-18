package com.pharmacore.sales.application;

import com.pharmacore.identity.persistence.AppUserRepository;
import com.pharmacore.sales.domain.SellerProfile;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Rules are evaluated against the cashier who rang up the sale, which for an offline replay may
 * differ from the user whose token is synchronizing the terminal journal.
 */
@Component
class SellerProfiles {

    private final AppUserRepository users;

    SellerProfiles(AppUserRepository users) {
        this.users = users;
    }

    SellerProfile resolve(UUID cashierUserId) {
        var current = TenantContext.requireTenantUser();
        if (cashierUserId == null || cashierUserId.equals(current.userId())) {
            return new SellerProfile(current.userId(), current.permissions(), current.maxDiscountPercent());
        }
        var cashier = users.findWithAuthoritiesById(cashierUserId)
                .orElseThrow(() -> new BusinessRuleException("unknown-cashier",
                        "Cashier " + cashierUserId + " does not belong to this pharmacy"));
        return new SellerProfile(cashier.id(), cashier.permissionCodes(), cashier.maxDiscountPercent());
    }
}
