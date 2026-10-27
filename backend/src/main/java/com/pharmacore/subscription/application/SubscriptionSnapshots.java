package com.pharmacore.subscription.application;

import com.pharmacore.subscription.domain.LicenseClaims;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cloud's own view of a subscription (tenant row and plan), from which it signs licenses
 * and evaluates the state of the pharmacies it serves directly. Requires a context that sees
 * the tenant row: the pharmacy itself or the platform.
 */
@Component
public class SubscriptionSnapshots {

    public record Snapshot(UUID tenantId, String slug, String status, String plan, int maxTerminals,
                           Instant paidUntil, Instant suspendedAt) {

        /** Anything but ACTIVE (suspended, terminated) is a suspension for the license. */
        public LicenseClaims claims(Instant issuedAt, Instant validUntil, long serial, Duration grace) {
            boolean suspended = !"ACTIVE".equals(status);
            return new LicenseClaims(tenantId, slug, plan, maxTerminals,
                    suspended ? LicenseClaims.Status.SUSPENDED : LicenseClaims.Status.ACTIVE,
                    paidUntil, paidUntil == null ? null : paidUntil.plus(grace),
                    suspended ? (suspendedAt == null ? issuedAt : suspendedAt) : null,
                    issuedAt, validUntil, serial);
        }
    }

    private final JdbcClient jdbc;

    public SubscriptionSnapshots(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Snapshot> read(UUID tenantId) {
        return jdbc.sql("""
                        SELECT t.slug, t.status, t.subscription_plan, t.subscription_expires_at, t.suspended_at,
                               coalesce(p.max_terminals, 1) AS max_terminals
                        FROM tenant t LEFT JOIN subscription_plan p ON p.code = t.subscription_plan
                        WHERE t.id = :tenant""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> new Snapshot(tenantId, rs.getString("slug"), rs.getString("status"),
                        rs.getString("subscription_plan"), rs.getInt("max_terminals"),
                        instant(rs.getTimestamp("subscription_expires_at")), instant(rs.getTimestamp("suspended_at"))))
                .optional();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
