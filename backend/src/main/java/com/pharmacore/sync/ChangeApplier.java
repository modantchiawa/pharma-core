package com.pharmacore.sync;

import com.pharmacore.sync.SyncModels.Change;
import com.pharmacore.sync.SyncTableRegistry.TableMeta;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Replays changes generically: every row is upserted from its JSON image through
 * {@code jsonb_populate_record}, so no per-table code exists and new columns are picked up
 * automatically. The capture trigger is muted for the transaction (no echo) and foreign keys
 * are deferred to commit.
 */
@Component
public class ChangeApplier {

    private final JdbcClient jdbc;
    private final SyncTableRegistry registry;

    public ChangeApplier(JdbcClient jdbc, SyncTableRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int apply(List<Change> changes) {
        prepareTransaction();
        int applied = 0;
        for (Change change : changes) {
            if (registry.find(change.table()).isEmpty()) {
                continue;
            }
            switch (change.op()) {
                case 'I', 'U' -> upsert(change.table(), change.row());
                case 'D' -> {
                    if (registry.meta(change.table()).appendOnly()) {
                        continue; // immutable rows are never deleted by either side
                    }
                    delete(change.table(), change.pk());
                }
                default -> throw new IllegalArgumentException("Unknown change op " + change.op());
            }
            applied++;
        }
        finishTransaction();
        return applied;
    }

    /** Snapshot rows (bootstrap / recovery) use the same upsert. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int upsertAll(String table, List<JsonNode> rows) {
        prepareTransaction();
        for (JsonNode row : rows) {
            upsert(table, row);
        }
        finishTransaction();
        return rows.size();
    }

    /** Replayed rows must not be captured again: they already carry their origin's position. */
    private void prepareTransaction() {
        jdbc.sql("SELECT set_config('app.sync_apply', 'on', true)").query(String.class).single();
        jdbc.sql("SET CONSTRAINTS ALL DEFERRED").update();
    }

    /** Anything the caller writes after the replay (audit, cursors) is this side's own change. */
    private void finishTransaction() {
        jdbc.sql("SELECT set_config('app.sync_apply', 'off', true)").query(String.class).single();
    }

    private void upsert(String table, JsonNode row) {
        TableMeta meta = registry.meta(table);
        String columns = meta.columns().stream().map(ChangeApplier::quote).collect(Collectors.joining(", "));
        String pk = meta.primaryKey().stream().map(ChangeApplier::quote).collect(Collectors.joining(", "));
        String updates = meta.columns().stream()
                .filter(column -> !meta.primaryKey().contains(column))
                .map(column -> quote(column) + " = EXCLUDED." + quote(column))
                .collect(Collectors.joining(", "));
        // Append-only tables (audit chain, stock movements, journal) are replayed as pure inserts:
        // a row already present is the same row, and the role has no UPDATE privilege on them.
        String sql = "INSERT INTO " + quote(table) + " (" + columns + ") "
                + "SELECT " + columns + " FROM jsonb_populate_record(NULL::" + quote(table) + ", :row::jsonb) "
                + "ON CONFLICT (" + pk + ") DO "
                + (updates.isEmpty() || meta.appendOnly() ? "NOTHING" : "UPDATE SET " + updates);
        jdbc.sql(sql).param("row", row.toString()).update();
    }

    private void delete(String table, JsonNode pk) {
        TableMeta meta = registry.meta(table);
        String pkColumns = meta.primaryKey().stream().map(ChangeApplier::quote).collect(Collectors.joining(", "));
        String sql = "DELETE FROM " + quote(table) + " WHERE (" + pkColumns + ") = "
                + "(SELECT " + pkColumns + " FROM jsonb_populate_record(NULL::" + quote(table) + ", :pk::jsonb))";
        jdbc.sql(sql).param("pk", pk.toString()).update();
    }

    private static String quote(String identifier) {
        if (!identifier.matches("^[a-z_][a-z0-9_]{0,62}$")) {
            throw new IllegalArgumentException("Unsafe identifier " + identifier);
        }
        return "\"" + identifier + "\"";
    }
}
