package com.pharmacore.subscription.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.subscription.application.SubscriptionSnapshots.Snapshot;
import com.pharmacore.subscription.domain.LicenseEvaluator;
import com.pharmacore.subscription.domain.LicenseState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one place that answers "may this pharmacy sell and modify right now?".
 * <ul>
 *   <li>Cloud: from its own database (tenant row), cached a few seconds and dropped on every
 *       subscription event, so a suspension applies at once.</li>
 *   <li>Hub: from the signed license it holds and its clock watermark, without any network.</li>
 * </ul>
 */
@Service
public class LicenseStateService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final NodeProperties node;
    private final HubLicenseService hub;
    private final SubscriptionSnapshots snapshots;
    private final SubscriptionProperties properties;
    private final TransactionTemplate readOnly;
    private final Clock clock;
    private final Cache<UUID, Optional<Snapshot>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL).maximumSize(100_000).build();

    public LicenseStateService(NodeProperties node, HubLicenseService hub, SubscriptionSnapshots snapshots,
                               SubscriptionProperties properties, PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.node = node;
        this.hub = hub;
        this.snapshots = snapshots;
        this.properties = properties;
        // Its own connection, bound to the pharmacy, whatever the caller is doing
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public LicenseState current(TenantId tenant) {
        if (node.isHub()) {
            return hub.state();
        }
        Instant now = clock.instant();
        var claims = snapshot(tenant.value()).map(s -> s.claims(now, null, 0, properties.grace())).orElse(null);
        return LicenseEvaluator.evaluate(claims, now, null, null, properties.policy());
    }

    public void evict(UUID tenant) {
        cache.invalidate(tenant);
    }

    private Optional<Snapshot> snapshot(UUID tenant) {
        return cache.get(tenant, id -> TenantContext.call(
                RequestContext.of(Actor.SystemProcess.forTenant("license-state", new TenantId(id))),
                () -> readOnly.execute(status -> snapshots.read(id))));
    }
}
