package com.pharmacore.sales.application;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Who sees which sales. A seller without {@link #PERMISSION} sees only what they sold and
 * cashed; the pharmacist and the managers see every seller (V25).
 */
public final class SalesVisibility {

    /** See the sales and receipts of every seller. */
    public static final String PERMISSION = "SALES_VIEW_ALL";

    private SalesVisibility() {
    }

    /**
     * The seller the current user is confined to, or empty when they see every seller.
     *
     * @param alsoSeeingAll other permissions that open the whole list (reconciling the payments
     *                      of the day needs all of them, whoever sold)
     */
    public static Optional<UUID> restrictedSeller(String... alsoSeeingAll) {
        if (!(TenantContext.actor() instanceof Actor.TenantUser user)) {
            return Optional.empty();
        }
        boolean seesAll = user.can(PERMISSION) || Arrays.stream(alsoSeeingAll).anyMatch(user::can);
        return seesAll ? Optional.empty() : Optional.of(user.userId());
    }

    /** Restricted seller, or the current user when {@code mineOnly} is asked by someone who sees all. */
    public static Optional<UUID> sellerFilter(boolean mineOnly) {
        Optional<UUID> restricted = restrictedSeller();
        if (restricted.isPresent() || !mineOnly) {
            return restricted;
        }
        return TenantContext.actor() instanceof Actor.TenantUser user ? Optional.of(user.userId()) : Optional.empty();
    }
}
