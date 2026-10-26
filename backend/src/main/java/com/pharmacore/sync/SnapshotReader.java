package com.pharmacore.sync;

import com.pharmacore.shared.context.TenantId;
import com.pharmacore.sync.SyncModels.SnapshotPage;
import com.pharmacore.sync.SyncTableRegistry.SyncTable;
import com.pharmacore.sync.SyncTableRegistry.TableMeta;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full-table extraction for a pharmacy (bootstrap of a new hub, recovery after the log was
 * purged, restore of a reinstalled hub from its cloud replica). Tables with a single-column
 * primary key are paged by key; the few composite-key tables are small and read in one page.
 */
@Component
public class SnapshotReader {

    private final JdbcClient jdbc;
    private final SyncTableRegistry registry;
    private final ObjectMapper json;

    public SnapshotReader(JdbcClient jdbc, SyncTableRegistry registry, ObjectMapper json) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SnapshotPage read(String table, TenantId tenant, String after, int limit) {
        SyncTable sync = registry.find(table).orElseThrow(() -> new IllegalArgumentException("Unknown table " + table));
        TableMeta meta = registry.meta(table);
        String tenantFilter = sync.global() ? "true"
                : meta.columns().contains("tenant_id") ? "t.tenant_id = :tenant" : "t.id = :tenant";
        if (meta.primaryKey().size() != 1) {
            List<JsonNode> rows = jdbc.sql("SELECT to_jsonb(t)::text AS row FROM \"" + table + "\" t WHERE " + tenantFilter)
                    .param("tenant", tenant.value())
                    .query((rs, rowNum) -> json.readTree(rs.getString("row")))
                    .list();
            return new SnapshotPage(table, rows, null, false);
        }
        String key = meta.primaryKey().getFirst();
        List<JsonNode> rows = jdbc.sql("SELECT to_jsonb(t)::text AS row, t.\"" + key + "\"::text AS key "
                        + "FROM \"" + table + "\" t WHERE " + tenantFilter
                        + " AND (:after::text IS NULL OR t.\"" + key + "\"::text > :after::text) "
                        + "ORDER BY t.\"" + key + "\"::text LIMIT :limit")
                .param("tenant", tenant.value())
                .param("after", after)
                .param("limit", limit)
                .query((rs, rowNum) -> json.readTree(rs.getString("row")))
                .list();
        boolean hasMore = rows.size() == limit;
        String nextAfter = rows.isEmpty() ? after : rows.getLast().get(key).asString();
        return new SnapshotPage(table, rows, nextAfter, hasMore);
    }
}
