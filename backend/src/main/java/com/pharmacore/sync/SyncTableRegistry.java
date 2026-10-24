package com.pharmacore.sync;

import com.pharmacore.shared.runtime.DeploymentMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The ownership map ({@code sync_table}) and the primary-key metadata needed to replay changes. */
@Component
public class SyncTableRegistry {

    public record SyncTable(String name, DeploymentMode owner, boolean global, String originColumn, int applyOrder) {

        /** Does {@code side} publish changes of this table to the other side? */
        public boolean publishedBy(DeploymentMode side) {
            return owner == side || originColumn != null;
        }
    }

    /** @param appendOnly the application role may insert but never update or delete (audit, movements, journal) */
    public record TableMeta(String name, List<String> columns, List<String> primaryKey, boolean appendOnly) {
    }

    private final JdbcClient jdbc;
    private volatile Map<String, SyncTable> tables;
    private final Map<String, TableMeta> meta = new java.util.concurrent.ConcurrentHashMap<>();

    public SyncTableRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SyncTable> all() {
        return tables().values().stream().sorted(java.util.Comparator.comparingInt(SyncTable::applyOrder)).toList();
    }

    public Optional<SyncTable> find(String name) {
        return Optional.ofNullable(tables().get(name));
    }

    /** Tables whose rows the given side sends to its peer (owned, or shared-by-origin). */
    public List<SyncTable> publishedBy(DeploymentMode side) {
        return all().stream().filter(t -> t.publishedBy(side)).toList();
    }

    public TableMeta meta(String table) {
        var known = find(table).orElseThrow(() -> new IllegalArgumentException("Table " + table + " is not synchronized"));
        return meta.computeIfAbsent(known.name(), name -> {
            List<String> columns = jdbc.sql("""
                            SELECT column_name FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = :table
                            ORDER BY ordinal_position""")
                    .param("table", name).query(String.class).list();
            List<String> pk = jdbc.sql("""
                            SELECT a.attname
                            FROM pg_index i
                            JOIN unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                            WHERE i.indrelid = :table::regclass AND i.indisprimary
                            ORDER BY k.ord""")
                    .param("table", name).query(String.class).list();
            boolean appendOnly = !jdbc.sql("SELECT has_table_privilege(current_user, :table, 'UPDATE')")
                    .param("table", name).query(Boolean.class).single();
            return new TableMeta(name, columns, pk, appendOnly);
        });
    }

    private Map<String, SyncTable> tables() {
        if (tables == null) {
            tables = jdbc.sql("SELECT table_name, owner_side, scope, origin_column, apply_order FROM sync_table")
                    .query((rs, rowNum) -> new SyncTable(rs.getString("table_name"),
                            DeploymentMode.valueOf(rs.getString("owner_side")),
                            "GLOBAL".equals(rs.getString("scope")), rs.getString("origin_column"),
                            rs.getInt("apply_order")))
                    .list().stream()
                    .collect(Collectors.toUnmodifiableMap(SyncTable::name, Function.identity()));
        }
        return tables;
    }
}
