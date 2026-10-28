package com.pharmacore.sales.web;

import com.pharmacore.sales.application.PaymentNotificationReceived;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes a committed payment confirmation to every terminal of the pharmacy over this node's
 * STOMP broker: on a hub, the local network is enough, no Internet needed.
 */
@Component
class PaymentNotificationRelay {

    static final String TOPIC_SUFFIX = ".payments";

    private final SimpMessagingTemplate broker;

    PaymentNotificationRelay(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onNotification(PaymentNotificationReceived event) {
        broker.convertAndSend("/topic/tenants." + event.tenantId() + TOPIC_SUFFIX, event.notification());
    }
}
