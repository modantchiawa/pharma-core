package com.pharmacore.sync;

import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.DeploymentMode;
import com.pharmacore.sync.SyncModels.Change;
import com.pharmacore.sync.SyncModels.ChangeBatch;
import com.pharmacore.sync.SyncModels.LogPosition;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the local change log for one pharmacy, from the side's point of view: only the tables
 * this side publishes, only transactions already finished (xid below the snapshot's xmin).
 */
@Component
public class ChangeLogReader {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ChangeLogReader(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ChangeBatch read(TenantId tenant, DeploymentMode side, LogPosition after, int limit) {
        List<Change> changes = jdbc.sql("""
                        SELECT c.xid, c.seq, c.tenant_id, c.table_name, c.op, c.pk::text AS pk, c.row::text AS row
                        FROM sync_change c
                        JOIN sync_table t ON t.table_name = c.table_name
                        WHERE (c.xid, c.seq) > (:xid, :seq)
                          AND c.xid < pg_snapshot_xmin(pg_current_snapshot())::text::bigint
                          AND (c.tenant_id = :tenant
                               OR (t.scope = 'GLOBAL' AND c.tenant_id = '00000000-0000-0000-0000-000000000000'::uuid))
                          AND (t.owner_side = :side
                               OR (t.origin_column IS NOT NULL AND c.row ->> t.origin_column = :side))
                        ORDER BY c.xid, c.seq
                        LIMIT :limit""")
                .param("xid", after.xid())
                .param("seq", after.seq())
                .param("tenant", tenant.value())
                .param("side", side.name())
                .param("limit", limit)
                .query((rs, rowNum) -> new Change(
                        rs.getLong("xid"),
                        rs.getLong("seq"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("table_name"),
                        rs.getString("op").charAt(0),
                        parse(rs.getString("pk")),
                        parse(rs.getString("row"))))
                .list();
        if (changes.isEmpty()) {
            return ChangeBatch.empty(after);
        }
        return new ChangeBatch(changes, changes.getLast().position(), changes.size() == limit);
    }

    /** Current end of the log: everything a snapshot taken now will contain is at or before it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LogPosition head() {
        return jdbc.sql("""
                        SELECT coalesce(max(xid), 0) AS xid, coalesce(max(seq), 0) AS seq
                        FROM sync_change
                        WHERE xid < pg_snapshot_xmin(pg_current_snapshot())::text::bigint""")
                .query((rs, rowNum) -> new LogPosition(rs.getLong("xid"), rs.getLong("seq")))
                .single();
    }

    /** Pending (unsent) changes, for the status screen. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long countAfter(TenantId tenant, DeploymentMode side, LogPosition after) {
        return jdbc.sql("""
                        SELECT count(*) FROM sync_change c
                        JOIN sync_table t ON t.table_name = c.table_name
                        WHERE (c.xid, c.seq) > (:xid, :seq)
                          AND (c.tenant_id = :tenant
                               OR (t.scope = 'GLOBAL' AND c.tenant_id = '00000000-0000-0000-0000-000000000000'::uuid))
                          AND (t.owner_side = :side
                               OR (t.origin_column IS NOT NULL AND c.row ->> t.origin_column = :side))""")
                .param("xid", after.xid())
                .param("seq", after.seq())
                .param("tenant", tenant.value())
                .param("side", side.name())
                .query(Long.class)
                .single();
    }

    private JsonNode parse(String text) {
        return text == null ? null : json.readTree(text);
    }
}
