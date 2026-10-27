package com.pharmacore.subscription.application;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hub's license entries in {@code node_setting}. The license is read and written while a
 * staff member's request is being served, whose connection only sees that pharmacy's rows: each
 * access therefore runs in its own transaction as a system process (node settings are reserved
 * to system processes).
 */
@Component
class HubLicenseSettings {

    private final JdbcClient jdbc;
    private final TransactionTemplate txNew;

    HubLicenseSettings(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.txNew = new TransactionTemplate(transactionManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Optional<String> get(String key) {
        return asSystem(() -> txNew.execute(status -> jdbc.sql("SELECT value FROM node_setting WHERE key = :key")
                .param("key", key).query(String.class).optional()));
    }

    void put(String key, String value) {
        asSystem(() -> txNew.execute(status -> jdbc.sql("""
                        INSERT INTO node_setting (key, value, updated_at) VALUES (:key, :value, now())
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                .param("key", key).param("value", value).update()));
    }

    private static <T> T asSystem(Supplier<T> action) {
        return TenantContext.call(RequestContext.of(Actor.SystemProcess.crossTenant("hub-license")), action);
    }
}
