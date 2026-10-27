package com.pharmacore.subscription.application;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.subscription.domain.LicenseClaims;
import com.pharmacore.tenant.application.TenantSubscriptionChanged;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cloud side: signs the license of a pharmacy from its subscription (plan, paid period,
 * suspension) and keeps the one in force in {@code tenant_license}, which replicates to its hub.
 * Re-issued on every subscription event and, for a hub, whenever it asks and the license it
 * would get is older than {@link #REFRESH_AFTER}: the offline window slides while the hub is
 * online.
 */
@Service
public class LicenseIssuer {

    static final Duration REFRESH_AFTER = Duration.ofHours(1);

    public record IssuedLicense(UUID tenantId, long serial, String kid, String token, Instant issuedAt,
                                Instant validUntil) {
    }

    private final JdbcClient jdbc;
    private final SubscriptionSnapshots snapshots;
    private final LicenseKeyRing keys;
    private final LicenseStateService states;
    private final SubscriptionProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LicenseIssuer(JdbcClient jdbc, SubscriptionSnapshots snapshots, LicenseKeyRing keys,
                         LicenseStateService states, SubscriptionProperties properties,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.keys = keys;
        this.states = states;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @EventListener
    void onSubscriptionChanged(TenantSubscriptionChanged event) {
        issue(event.tenantId());
    }

    /** Signs a new license (next serial) and makes it the one in force. */
    public IssuedLicense issue(UUID tenantId) {
        IssuedLicense issued = asPlatform(() -> tx.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "license:" + tenantId)
                    .query((RowCallbackHandler) rs -> { });
            Instant now = clock.instant();
            long serial = jdbc.sql("SELECT serial FROM tenant_license WHERE tenant_id = :tenant")
                    .param("tenant", tenantId).query(Long.class).optional().orElse(0L) + 1;
            LicenseClaims claims = snapshots.read(tenantId)
                    .orElseThrow(() -> new NotFoundException("Tenant", tenantId))
                    .claims(now, now.plus(properties.offlineWindow()), serial, properties.grace());
            String token = keys.sign(claims);
            jdbc.sql("""
                            INSERT INTO tenant_license (tenant_id, serial, kid, token, issued_at, valid_until)
                            VALUES (:tenant, :serial, :kid, :token, :issuedAt, :validUntil)
                            ON CONFLICT (tenant_id) DO UPDATE SET serial = EXCLUDED.serial, kid = EXCLUDED.kid,
                                token = EXCLUDED.token, issued_at = EXCLUDED.issued_at,
                                valid_until = EXCLUDED.valid_until, version = tenant_license.version + 1""")
                    .param("tenant", tenantId)
                    .param("serial", serial)
                    .param("kid", keys.activeKid())
                    .param("token", token)
                    .param("issuedAt", Timestamp.from(claims.issuedAt()))
                    .param("validUntil", Timestamp.from(claims.validUntil()))
                    .update();
            return new IssuedLicense(tenantId, serial, keys.activeKid(), token, claims.issuedAt(), claims.validUntil());
        }));
        states.evict(tenantId);
        return issued;
    }

    /** The license in force, re-issued when it is old enough for the offline window to slide. */
    public IssuedLicense current(UUID tenantId) {
        var stored = asPlatform(() -> tx.execute(status -> jdbc.sql("""
                        SELECT serial, kid, token, issued_at, valid_until FROM tenant_license WHERE tenant_id = :tenant""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> new IssuedLicense(tenantId, rs.getLong("serial"), rs.getString("kid"),
                        rs.getString("token"), rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("valid_until").toInstant()))
                .optional()));
        Instant now = clock.instant();
        return stored.filter(license -> license.issuedAt().plus(REFRESH_AFTER).isAfter(now)
                        && license.kid().equals(keys.activeKid()))
                .orElseGet(() -> issue(tenantId));
    }

    private static <T> T asPlatform(java.util.function.Supplier<T> action) {
        return TenantContext.call(RequestContext.of(Actor.SystemProcess.crossTenant("license-issuer")), action);
    }
}
