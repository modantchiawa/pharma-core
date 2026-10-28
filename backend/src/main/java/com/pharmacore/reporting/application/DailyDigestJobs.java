package com.pharmacore.reporting.application;

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
 * Publishes the evening report once the pharmacy's chosen hour has passed. Checked every few
 * minutes so a hub restarted late in the evening still sends it; a hub serves its pharmacy, the
 * cloud the pharmacies it is master of.
 */
@Component
class DailyDigestJobs {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestJobs.class);

    private final DailyDigestService digests;
    private final NodeIdentity node;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    DailyDigestJobs(DailyDigestService digests, NodeIdentity node, JdbcClient jdbc,
                    PlatformTransactionManager transactionManager) {
        this.digests = digests;
        this.node = node;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Scheduled(fixedDelayString = "${pharmacore.reporting.digest-delay:300000}",
            initialDelayString = "${pharmacore.reporting.digest-delay:300000}")
    void publishDueDigests() {
        List<TenantId> tenants = node.hubTenant().map(List::of).orElseGet(this::cloudMastered);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TenantId tenant : tenants) {
                executor.submit(() -> {
                    var context = RequestContext.of(Actor.SystemProcess.forTenant("daily-digest", tenant));
                    try {
                        TenantContext.run(context, () -> digests.dueNow().ifPresent(date -> {
                            digests.publish(date);
                            log.info("Pharmacie {} : rapport du {} publié", tenant, date);
                        }));
                    } catch (RuntimeException e) {
                        log.warn("Rapport du soir en échec pour {} : {}", tenant, e.getMessage());
                    }
                });
            }
        }
    }

    private List<TenantId> cloudMastered() {
        var discovery = RequestContext.of(Actor.SystemProcess.crossTenant("daily-digest-jobs"));
        return TenantContext.call(discovery, () -> readOnly.execute(status -> jdbc.sql("""
                        SELECT id FROM tenant WHERE status = 'ACTIVE' AND topology = 'CLOUD' ORDER BY id""")
                .query(UUID.class).list().stream().map(TenantId::new).toList()));
    }
}
