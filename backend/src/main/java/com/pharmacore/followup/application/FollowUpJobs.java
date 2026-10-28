package com.pharmacore.followup.application;

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
 * Patient follow-up loops. A hub runs them for its pharmacy; the cloud for the pharmacies it is
 * master of (cloud topology): for a hub pharmacy the rows on the cloud are replicas.
 */
@Component
class FollowUpJobs {

    private static final Logger log = LoggerFactory.getLogger(FollowUpJobs.class);

    private final FollowUpGenerator generator;
    private final SmsDispatcher sms;
    private final NodeIdentity node;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    FollowUpJobs(FollowUpGenerator generator, SmsDispatcher sms, NodeIdentity node, JdbcClient jdbc,
                 PlatformTransactionManager transactionManager) {
        this.generator = generator;
        this.sms = sms;
        this.node = node;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** Hourly: the generator is idempotent, so running it often costs nothing and survives restarts. */
    @Scheduled(cron = "${pharmacore.followup.generate-cron:0 7 * * * *}")
    void generateMessages() {
        forEachPharmacy("followup-generate", tenant -> {
            var report = generator.generate();
            if (report.total() > 0) {
                log.info("Pharmacie {} : {} message(s) de suivi préparé(s)", tenant, report.total());
            }
        });
    }

    @Scheduled(fixedDelayString = "${pharmacore.followup.sms-delay:60000}",
            initialDelayString = "${pharmacore.followup.sms-delay:60000}")
    void deliverSms() {
        forEachPharmacy("followup-sms", tenant -> {
            int sent = sms.deliverDue();
            if (sent > 0) {
                log.info("Pharmacie {} : {} SMS envoyé(s)", tenant, sent);
            }
        });
    }

    private void forEachPharmacy(String process, Consumer<TenantId> action) {
        List<TenantId> tenants = node.hubTenant().map(List::of).orElseGet(this::cloudMastered);
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

    private List<TenantId> cloudMastered() {
        var discovery = RequestContext.of(Actor.SystemProcess.crossTenant("followup-jobs"));
        return TenantContext.call(discovery, () -> readOnly.execute(status -> jdbc.sql("""
                        SELECT id FROM tenant WHERE status = 'ACTIVE' AND topology = 'CLOUD' ORDER BY id""")
                .query(UUID.class).list().stream().map(TenantId::new).toList()));
    }
}
