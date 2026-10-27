package com.pharmacore.control.application;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook delivery loop. A hub delivers its own pharmacy's alerts; the cloud delivers those of
 * the pharmacies it is master of (cloud topology): the outbox rows of a hub pharmacy are
 * replicas, the hub sends them itself.
 */
@Component
class ControlJobs {

    private static final Logger log = LoggerFactory.getLogger(ControlJobs.class);

    private final WebhookDispatcher dispatcher;
    private final NodeIdentity node;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    ControlJobs(WebhookDispatcher dispatcher, NodeIdentity node, JdbcClient jdbc,
                PlatformTransactionManager transactionManager) {
        this.dispatcher = dispatcher;
        this.node = node;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Scheduled(fixedDelayString = "${pharmacore.control.webhook-delay:30000}",
            initialDelayString = "${pharmacore.control.webhook-delay:30000}")
    void deliverWebhooks() {
        List<TenantId> tenants = node.hubTenant().map(List::of).orElseGet(this::cloudMastered);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TenantId tenant : tenants) {
                executor.submit(() -> {
                    var context = RequestContext.of(Actor.SystemProcess.forTenant("control-webhook", tenant));
                    try {
                        TenantContext.run(context, () -> {
                            int delivered = dispatcher.deliverDue();
                            if (delivered > 0) {
                                log.info("Pharmacie {} : {} alerte(s) livrée(s) au webhook", tenant, delivered);
                            }
                        });
                    } catch (RuntimeException e) {
                        log.warn("Livraison des alertes en échec pour {} : {}", tenant, e.getMessage());
                    }
                });
            }
        }
    }

    private List<TenantId> cloudMastered() {
        var discovery = RequestContext.of(Actor.SystemProcess.crossTenant("control-jobs"));
        return TenantContext.call(discovery, () -> readOnly.execute(status -> jdbc.sql("""
                        SELECT t.id FROM tenant t
                        WHERE t.status = 'ACTIVE' AND t.topology = 'CLOUD'
                          AND EXISTS (SELECT 1 FROM control_webhook_outbox o
                                      WHERE o.tenant_id = t.id AND o.status = 'PENDING')
                        ORDER BY t.id""")
                .query(UUID.class).list().stream().map(TenantId::new).toList()));
    }
}
