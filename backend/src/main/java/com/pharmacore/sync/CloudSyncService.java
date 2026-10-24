package com.pharmacore.sync;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.DeploymentMode;
import com.pharmacore.shared.runtime.NodeSettings;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.sync.SyncModels.Change;
import com.pharmacore.sync.SyncModels.ChangeBatch;
import com.pharmacore.sync.SyncModels.LogPosition;
import com.pharmacore.sync.SyncModels.PullResponse;
import com.pharmacore.sync.SyncModels.PushRequest;
import com.pharmacore.sync.SyncModels.PushResponse;
import com.pharmacore.sync.SyncModels.SnapshotPage;
import com.pharmacore.sync.SyncModels.SnapshotStart;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cloud side of the protocol: receives hub pushes, serves pulls and snapshots. */
@Service
public class CloudSyncService {

    static final int MAX_BATCH = 2_000;

    private final ChangeApplier applier;
    private final ChangeLogReader reader;
    private final SnapshotReader snapshots;
    private final SyncTableRegistry registry;
    private final NodeSettings settings;
    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public CloudSyncService(ChangeApplier applier, ChangeLogReader reader, SnapshotReader snapshots,
                            SyncTableRegistry registry, NodeSettings settings, JdbcClient jdbc, AuditTrail audit) {
        this.applier = applier;
        this.reader = reader;
        this.snapshots = snapshots;
        this.registry = registry;
        this.settings = settings;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * Applies a hub batch atomically. Changes at or before the last acknowledged position are
     * skipped, so a hub that lost the previous acknowledgement can resend safely.
     */
    @Transactional
    public PushResponse push(PushRequest request) {
        var hub = hubNode();
        LogPosition acknowledged = jdbc.sql("""
                        SELECT last_push_xid, last_push_seq FROM hub_node
                        WHERE tenant_id = :tenant AND node_id = :node FOR UPDATE""")
                .param("tenant", hub.tenant().value())
                .param("node", hub.nodeId())
                .query((rs, rowNum) -> new LogPosition(rs.getLong("last_push_xid"), rs.getLong("last_push_seq")))
                .optional()
                .orElseThrow(() -> new BusinessRuleException("hub-unknown", "This hub is not paired"));
        List<Change> fresh = request.changes().stream()
                .filter(change -> change.position().isAfter(acknowledged))
                .filter(change -> hub.tenant().value().equals(change.tenantId()))
                .filter(change -> registry.find(change.table()).map(t -> t.publishedBy(DeploymentMode.HUB)).orElse(false))
                .toList();
        int applied = applier.apply(fresh);
        LogPosition last = fresh.isEmpty() ? acknowledged : fresh.getLast().position();
        jdbc.sql("""
                        UPDATE hub_node SET last_push_xid = :xid, last_push_seq = :seq, last_push_at = now(),
                                            last_seen_at = now(), app_version = coalesce(:version, app_version)
                        WHERE tenant_id = :tenant AND node_id = :node""")
                .param("xid", last.xid())
                .param("seq", last.seq())
                .param("version", request.appVersion())
                .param("tenant", hub.tenant().value())
                .param("node", hub.nodeId())
                .update();
        if (applied > 0) {
            // Traceability of what the cloud replica received, visible from the hub after the next pull
            audit.record("SYNC_PUSH_APPLIED", "HUB_NODE", hub.nodeId(), Map.of(
                    "applied", Integer.toString(applied),
                    "xid", Long.toString(last.xid()),
                    "seq", Long.toString(last.seq())));
        }
        return new PushResponse(last.xid(), last.seq(), applied, request.changes().size() - fresh.size());
    }

    @Transactional
    public PullResponse pull(LogPosition after, int limit) {
        var hub = hubNode();
        long purgeWatermark = settings.get("sync.purge_xid").map(Long::parseLong).orElse(0L);
        boolean resetRequired = after.xid() > 0 && after.xid() < purgeWatermark;
        ChangeBatch batch = resetRequired
                ? ChangeBatch.empty(after)
                : reader.read(hub.tenant(), DeploymentMode.CLOUD, after, Math.clamp(limit, 1, MAX_BATCH));
        jdbc.sql("UPDATE hub_node SET last_pull_at = now(), last_seen_at = now() WHERE tenant_id = :tenant AND node_id = :node")
                .param("tenant", hub.tenant().value()).param("node", hub.nodeId()).update();
        return new PullResponse(batch.changes(), batch.last().xid(), batch.last().seq(), batch.hasMore(), resetRequired);
    }

    /** Log position to resume from after a snapshot, plus the tables to fetch. */
    @Transactional(readOnly = true)
    public SnapshotStart snapshotStart(boolean includeHubOwned) {
        hubNode();
        LogPosition head = reader.head();
        List<String> tables = registry.all().stream()
                .filter(t -> includeHubOwned || t.publishedBy(DeploymentMode.CLOUD))
                .map(SyncTableRegistry.SyncTable::name)
                .toList();
        return new SnapshotStart(head.xid(), head.seq(), tables);
    }

    @Transactional(readOnly = true)
    public SnapshotPage snapshot(String table, String after, int limit) {
        var hub = hubNode();
        return snapshots.read(table, hub.tenant(), after, Math.clamp(limit, 1, MAX_BATCH));
    }

    private static Actor.HubNode hubNode() {
        if (TenantContext.actor() instanceof Actor.HubNode hub) {
            return hub;
        }
        throw new BusinessRuleException("hub-only", "Synchronization endpoints are reserved to paired hubs");
    }

    static TenantId tenantOf(Actor.HubNode hub) {
        return hub.tenant();
    }
}
