package com.pharmacore.sync;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.DeploymentMode;
import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.sync.SyncCursorStore.Stream;
import com.pharmacore.sync.SyncModels.ChangeBatch;
import com.pharmacore.sync.SyncModels.LogPosition;
import com.pharmacore.sync.SyncModels.PullResponse;
import com.pharmacore.sync.SyncModels.PushResponse;
import com.pharmacore.sync.SyncModels.SnapshotPage;
import com.pharmacore.sync.SyncModels.SnapshotStart;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Hub side of the protocol: pushes local changes, pulls cloud changes, bootstraps from a
 * snapshot. Runs on a schedule and on demand; tolerates any outage (every step is resumable
 * from a persisted cursor and every apply is idempotent).
 */
@Service
public class HubSyncService {

    private static final Logger log = LoggerFactory.getLogger(HubSyncService.class);
    private static final int PUSH_BATCH = 500;
    private static final int PULL_BATCH = 500;
    private static final int MAX_BATCH = 4_000;

    public record Status(boolean paired, boolean running, boolean cloudReachable, Instant lastPushAt, Instant lastPullAt,
                         long pendingChanges, LogPosition pushCursor, LogPosition pullCursor, String lastError,
                         boolean bootstrapping) {
    }

    private final NodeIdentity node;
    private final NodeProperties properties;
    private final HubCloudClient cloud;
    private final ChangeLogReader reader;
    private final ChangeApplier applier;
    private final SyncCursorStore cursors;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean running;
    private volatile boolean bootstrapping;
    private volatile boolean cloudReachable;
    private volatile Instant lastPushAt;
    private volatile Instant lastPullAt;
    private volatile String lastError;

    public HubSyncService(NodeIdentity node, NodeProperties properties, HubCloudClient cloud, ChangeLogReader reader,
                          ChangeApplier applier, SyncCursorStore cursors, PlatformTransactionManager transactionManager,
                          Clock clock) {
        this.node = node;
        this.properties = properties;
        this.cloud = cloud;
        this.reader = reader;
        this.applier = applier;
        this.cursors = cursors;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pharmacore.node.sync-interval:PT30S}", initialDelayString = "PT20S")
    public void scheduled() {
        if (properties.isHub() && node.isPaired()) {
            synchronize();
        }
    }

    /** One full round (push then pull). Concurrent calls are coalesced. */
    public Status synchronize() {
        if (!lock.tryLock()) {
            return status();
        }
        running = true;
        try {
            TenantId tenant = node.requireHubTenant();
            pushAll(tenant);
            pullAll(tenant);
            cloudReachable = true;
            lastError = null;
        } catch (ResourceAccessException e) {
            cloudReachable = false;
            lastError = "Cloud injoignable : " + e.getMostSpecificCause().getMessage();
            log.info("Synchronisation différée, cloud injoignable: {}", e.getMostSpecificCause().getMessage());
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.warn("Synchronisation en erreur", e);
        } finally {
            running = false;
            lock.unlock();
        }
        return status();
    }

    /** First synchronization after pairing: full snapshot from the cloud, then incremental. */
    public void bootstrap() {
        lock.lock();
        bootstrapping = true;
        try {
            TenantId tenant = node.requireHubTenant();
            SnapshotStart start = cloud.snapshotStart(true);
            for (String table : start.tables()) {
                String after = null;
                do {
                    SnapshotPage page = cloud.snapshot(table, after, PULL_BATCH);
                    asSystem(() -> tx.executeWithoutResult(status -> applier.upsertAll(table, page.rows())));
                    after = page.nextAfter();
                    if (!page.hasMore()) {
                        break;
                    }
                } while (true);
                log.info("Bootstrap: table {} chargée", table);
            }
            asSystem(() -> tx.executeWithoutResult(status ->
                    cursors.set(tenant, Stream.PULL, new LogPosition(start.xid(), start.seq()))));
            lastPullAt = clock.instant();
            cloudReachable = true;
            lastError = null;
        } finally {
            bootstrapping = false;
            lock.unlock();
        }
    }

    public Status status() {
        if (!properties.isHub() || !node.isPaired()) {
            return new Status(false, false, cloudReachable, null, null, 0, LogPosition.START, LogPosition.START,
                    lastError, bootstrapping);
        }
        TenantId tenant = node.requireHubTenant();
        return asSystem(() -> tx.execute(status -> {
            LogPosition push = cursors.get(tenant, Stream.PUSH);
            LogPosition pull = cursors.get(tenant, Stream.PULL);
            long pending = reader.countAfter(tenant, DeploymentMode.HUB, push);
            return new Status(true, running, cloudReachable, lastPushAt, lastPullAt, pending, push, pull, lastError,
                    bootstrapping);
        }));
    }

    private void pushAll(TenantId tenant) {
        int limit = PUSH_BATCH;
        for (;;) {
            LogPosition after = asSystem(() -> tx.execute(status -> cursors.get(tenant, Stream.PUSH)));
            int batchLimit = limit;
            ChangeBatch batch = asSystem(() -> tx.execute(status ->
                    reader.read(tenant, DeploymentMode.HUB, after, batchLimit)));
            if (batch.changes().isEmpty()) {
                return;
            }
            PushResponse response;
            try {
                response = cloud.push(batch.changes());
            } catch (HttpClientErrorException.Conflict e) {
                // The batch was cut inside a dependency: send more rows at once.
                if (limit >= MAX_BATCH) {
                    throw e;
                }
                limit = Math.min(limit * 2, MAX_BATCH);
                continue;
            }
            LogPosition acknowledged = new LogPosition(response.acceptedXid(), response.acceptedSeq());
            asSystem(() -> tx.executeWithoutResult(status -> cursors.set(tenant, Stream.PUSH, acknowledged)));
            lastPushAt = clock.instant();
            if (!batch.hasMore()) {
                return;
            }
        }
    }

    private void pullAll(TenantId tenant) {
        for (;;) {
            LogPosition after = asSystem(() -> tx.execute(status -> cursors.get(tenant, Stream.PULL)));
            PullResponse response = cloud.pull(after, PULL_BATCH);
            if (response.resetRequired()) {
                log.warn("Journal cloud purgé au-delà du curseur : rechargement complet");
                bootstrap();
                return;
            }
            if (response.changes().isEmpty()) {
                return;
            }
            LogPosition last = new LogPosition(response.lastXid(), response.lastSeq());
            asSystem(() -> tx.executeWithoutResult(status -> {
                applier.apply(response.changes());
                cursors.set(tenant, Stream.PULL, last);
            }));
            lastPullAt = clock.instant();
            if (!response.hasMore()) {
                return;
            }
        }
    }

    private <T> T asSystem(java.util.function.Supplier<T> action) {
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("hub-sync"));
        return TenantContext.call(context, action);
    }

    private void asSystem(Runnable action) {
        asSystem(() -> {
            action.run();
            return null;
        });
    }
}
