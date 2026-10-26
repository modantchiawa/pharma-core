package com.pharmacore.inventory.application;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nightly stock hygiene. A hub serves one pharmacy; the cloud iterates over every active
 * pharmacy, each on its own virtual thread and in its own tenant context, so one failure never
 * stops the others.
 */
@Component
class InventoryJobs {

    private static final Logger log = LoggerFactory.getLogger(InventoryJobs.class);

    private final StockIntegrityService integrity;
    private final ExpiryWriteOffService expiry;
    private final NodeIdentity node;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    InventoryJobs(StockIntegrityService integrity, ExpiryWriteOffService expiry, NodeIdentity node, JdbcClient jdbc,
                  PlatformTransactionManager transactionManager) {
        this.integrity = integrity;
        this.expiry = expiry;
        this.node = node;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Scheduled(cron = "${pharmacore.inventory.expiry-cron:0 20 1 * * *}")
    void writeOffExpiredLots() {
        forEachPharmacy("expiry-write-off", tenant -> {
            var removed = expiry.writeOffExpired();
            if (!removed.isEmpty()) {
                log.info("Pharmacie {} : {} lots périmés sortis du stock", tenant, removed.size());
            }
        });
    }

    @Scheduled(cron = "${pharmacore.inventory.integrity-cron:0 40 2 * * *}")
    void checkIntegrity() {
        forEachPharmacy("stock-integrity", tenant -> {
            var report = integrity.check();
            if (report.mismatches() > 0) {
                log.warn("Pharmacie {} : {} écarts d'intégrité du stock détectés", tenant, report.mismatches());
            }
        });
    }

    private void forEachPharmacy(String process, Consumer<TenantId> action) {
        List<TenantId> tenants = node.hubTenant().map(List::of).orElseGet(this::activeTenants);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TenantId tenant : tenants) {
                executor.submit(() -> {
                    var context = RequestContext.of(Actor.SystemProcess.forTenant(process, tenant));
                    try {
                        TenantContext.run(context, () -> action.accept(tenant));
                    } catch (RuntimeException e) {
                        log.warn("Traitement {} en échec pour {} : {}", process, tenant, e.getMessage());
                    }
                });
            }
        }
    }

    private List<TenantId> activeTenants() {
        var discovery = RequestContext.of(Actor.SystemProcess.crossTenant("inventory-jobs"));
        return TenantContext.call(discovery, () -> readOnly.execute(status ->
                jdbc.sql("SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id")
                        .query(UUID.class).list().stream().map(TenantId::new).toList()));
    }
}
