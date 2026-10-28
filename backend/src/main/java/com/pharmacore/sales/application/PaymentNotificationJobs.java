package com.pharmacore.sales.application;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.runtime.NodeIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** On a hub: turns the operator webhooks replicated from the cloud into local confirmations. */
@Component
class PaymentNotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotificationJobs.class);

    private final PaymentNotificationService notifications;
    private final NodeIdentity node;

    PaymentNotificationJobs(PaymentNotificationService notifications, NodeIdentity node) {
        this.notifications = notifications;
        this.node = node;
    }

    @Scheduled(fixedDelayString = "${pharmacore.payments.webhook-delay:60000}",
            initialDelayString = "${pharmacore.payments.webhook-delay:60000}")
    void ingestReplicatedWebhooks() {
        node.hubTenant().ifPresent(tenant -> {
            var context = RequestContext.of(Actor.SystemProcess.forTenant("payment-webhooks", tenant));
            try {
                int done = TenantContext.call(context, notifications::ingestReplicatedWebhooks);
                if (done > 0) {
                    log.info("{} confirmation(s) de paiement reçue(s) du cloud", done);
                }
            } catch (RuntimeException e) {
                log.warn("Traitement des webhooks de paiement en échec : {}", e.getMessage());
            }
        });
    }
}
