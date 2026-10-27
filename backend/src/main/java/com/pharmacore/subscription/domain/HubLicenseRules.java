package com.pharmacore.subscription.domain;

import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Anti-tamper rules of a hub, which enforces its license without the cloud: only a license
 * signed by a pinned key, for its own pharmacy, never older than the one it holds; and a
 * persisted "highest time seen" so that turning the clock back does not extend the license.
 */
public final class HubLicenseRules {

    private HubLicenseRules() {
    }

    /**
     * @param current the license the hub holds, {@code null} if none
     * @throws LicenseRejectedException when the candidate must be ignored
     */
    public static LicenseClaims accept(String token, Function<String, Optional<PublicKey>> pinnedKeys, UUID tenant,
                                       LicenseClaims current) {
        LicenseClaims claims = LicenseCodec.verify(token, pinnedKeys);
        if (!claims.tenantId().equals(tenant)) {
            throw new LicenseRejectedException(LicenseRejectedException.Reason.WRONG_TENANT);
        }
        if (current != null && claims.serial() < current.serial()) {
            throw new LicenseRejectedException(LicenseRejectedException.Reason.SERIAL_REGRESSION);
        }
        return claims;
    }

    /** The watermark only moves forward with the local clock. */
    public static Instant observe(Instant watermark, Instant now) {
        return watermark == null || now.isAfter(watermark) ? now : watermark;
    }

    /**
     * A fresh license carries the cloud's time. When the local clock agrees with it, the
     * watermark is re-anchored there (this is how a hub recovers after its clock was set far in
     * the future by mistake and then corrected); otherwise it can only move forward, and a clock
     * behind the cloud shows up as tampered.
     */
    public static Instant afterLicense(Instant watermark, Instant now, Instant issuedAt, Duration tolerance) {
        if (Duration.between(issuedAt, now).abs().compareTo(tolerance) <= 0) {
            return now.isAfter(issuedAt) ? now : issuedAt;
        }
        return observe(observe(watermark, now), issuedAt);
    }
}
