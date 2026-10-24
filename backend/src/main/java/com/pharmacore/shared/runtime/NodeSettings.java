package com.pharmacore.shared.runtime;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Key/value settings of this node ({@code node_setting}), read in a system context. */
@Component
public class NodeSettings {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public NodeSettings(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Optional<String> get(String key) {
        return system(() -> tx.execute(status ->
                jdbc.sql("SELECT value FROM node_setting WHERE key = :key").param("key", key)
                        .query(String.class).optional()));
    }

    public void put(String key, String value) {
        system(() -> {
            tx.executeWithoutResult(status -> jdbc.sql("""
                            INSERT INTO node_setting (key, value, updated_at) VALUES (:key, :value, now())
                            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                    .param("key", key).param("value", value).update());
            return null;
        });
    }

    private static <T> T system(Supplier<T> action) {
        if (TenantContext.current().isPresent()) {
            return action.get();
        }
        return TenantContext.call(RequestContext.of(Actor.SystemProcess.crossTenant("node-settings")), action);
    }
}
