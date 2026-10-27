package com.pharmacore.subscription.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the cloud vouches for about one pharmacy at {@code issuedAt}.
 *
 * @param paidUntil   end of the paid period, {@code null} for a subscription without end date
 * @param graceUntil  end of the grace period after {@code paidUntil}, {@code null} with it
 * @param suspendedAt when the vendor suspended the pharmacy, {@code null} unless suspended
 * @param validUntil  offline limit: a hub stops trusting the license after it; {@code null} for the
 *                    live view the cloud computes from its own database
 * @param serial      strictly increasing per pharmacy, so a hub never goes back to an older license
 */
public record LicenseClaims(UUID tenantId,
                            String slug,
                            String plan,
                            int maxTerminals,
                            Status status,
                            Instant paidUntil,
                            Instant graceUntil,
                            Instant suspendedAt,
                            Instant issuedAt,
                            Instant validUntil,
                            long serial) {

    public enum Status {
        ACTIVE,
        SUSPENDED
    }

    public LicenseClaims {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
