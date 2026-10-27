package com.pharmacore.subscription.domain;

import com.pharmacore.subscription.domain.LicenseState.Reason;
import com.pharmacore.subscription.domain.LicenseState.Status;
import java.time.Duration;
import java.time.Instant;

/**
 * The subscription state machine, identical on the cloud (live claims built from its database)
 * and on a hub (signed claims received from the cloud, plus the local clock watermark).
 * Precedence: clock tampering, missing license, suspension, offline limit, expiry, grace,
 * warnings.
 */
public final class LicenseEvaluator {

    private LicenseEvaluator() {
    }

    /**
     * @param claims       the license in force, {@code null} when the node holds none
     * @param now          the node's clock
     * @param watermark    highest time this node has seen, {@code null} on the cloud
     * @param missingSince on a hub that never received a license (upgrade from a version without
     *                     licenses), when that was first noticed: it gets the same offline window
     */
    public static LicenseState evaluate(LicenseClaims claims, Instant now, Instant watermark, Instant missingSince,
                                        LicensePolicy policy) {
        if (watermark != null && now.isBefore(watermark.minus(policy.clockTolerance()))) {
            return new LicenseState(Status.BLOCKED, Reason.CLOCK_TAMPERED, null, null, now, null, claims);
        }
        if (claims == null) {
            if (missingSince == null) {
                return new LicenseState(Status.BLOCKED, Reason.NO_LICENSE, null, null, now, null, null);
            }
            Instant limit = missingSince.plus(policy.offlineWindow());
            return now.isBefore(limit)
                    ? new LicenseState(Status.WARNING, Reason.NO_LICENSE, limit, daysLeft(now, limit), limit, null, null)
                    : new LicenseState(Status.BLOCKED, Reason.NO_LICENSE, limit, 0, limit, limit, null);
        }
        if (claims.status() == LicenseClaims.Status.SUSPENDED) {
            Instant since = claims.suspendedAt() == null ? claims.issuedAt() : claims.suspendedAt();
            return new LicenseState(Status.BLOCKED, Reason.SUSPENDED, null, null, since, since, claims);
        }
        Instant blocksAt = earliest(claims.graceUntil(), claims.validUntil());
        if (claims.validUntil() != null && !now.isBefore(claims.validUntil())) {
            return new LicenseState(Status.BLOCKED, Reason.OFFLINE_TOO_LONG, claims.validUntil(), 0, blocksAt,
                    claims.validUntil(), claims);
        }
        if (claims.graceUntil() != null && !now.isBefore(claims.graceUntil())) {
            return new LicenseState(Status.BLOCKED, Reason.EXPIRED, claims.graceUntil(), 0, blocksAt,
                    claims.graceUntil(), claims);
        }
        if (claims.paidUntil() != null && !now.isBefore(claims.paidUntil())) {
            Instant until = claims.graceUntil() == null ? claims.paidUntil() : claims.graceUntil();
            return new LicenseState(Status.GRACE, Reason.EXPIRED, until, daysLeft(now, until), blocksAt, null, claims);
        }
        Instant paymentWarningAt = claims.paidUntil() == null ? null : claims.paidUntil().minus(policy.paymentWarning());
        Instant offlineWarningAt = claims.validUntil() == null ? null : claims.validUntil().minus(policy.offlineWarning());
        boolean paymentDue = paymentWarningAt != null && !now.isBefore(paymentWarningAt);
        boolean offline = offlineWarningAt != null && !now.isBefore(offlineWarningAt);
        if (paymentDue && (!offline || !claims.validUntil().isBefore(claims.paidUntil()))) {
            return new LicenseState(Status.WARNING, Reason.PAYMENT_DUE, claims.paidUntil(),
                    daysLeft(now, claims.paidUntil()), blocksAt, null, claims);
        }
        if (offline) {
            return new LicenseState(Status.WARNING, Reason.OFFLINE_LIMIT, claims.validUntil(),
                    daysLeft(now, claims.validUntil()), blocksAt, null, claims);
        }
        return new LicenseState(Status.ACTIVE, null, null, null, blocksAt, null, claims);
    }

    /** Whole days left, rounded up: 6 days and 2 hours is « 7 jours ». */
    static int daysLeft(Instant now, Instant deadline) {
        long seconds = Duration.between(now, deadline).toSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (int) ((seconds + 86_399) / 86_400);
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
