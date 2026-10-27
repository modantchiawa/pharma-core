package com.pharmacore.control.web;

import com.pharmacore.control.application.ControlAlertRaised;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes a committed alert to the pharmacy's screens over the existing STOMP broker; the topic is
 * only open to holders of {@code CONTROL_VIEW} of that pharmacy.
 */
@Component
class ControlAlertRelay {

    static final String TOPIC_SUFFIX = ".control-alerts";

    private final SimpMessagingTemplate broker;

    ControlAlertRelay(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAlert(ControlAlertRaised event) {
        broker.convertAndSend("/topic/tenants." + event.tenantId() + TOPIC_SUFFIX, event.alert());
    }
}
