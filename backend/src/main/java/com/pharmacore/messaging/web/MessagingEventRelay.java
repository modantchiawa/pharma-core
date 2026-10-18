package com.pharmacore.messaging.web;

import com.pharmacore.messaging.application.ChatMessagePosted;
import com.pharmacore.network.domain.TransferChanged;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Pushes committed domain events to STOMP subscribers; nothing is sent for rolled-back work. */
@Component
class MessagingEventRelay {

    private final SimpMessagingTemplate broker;

    MessagingEventRelay(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMessage(ChatMessagePosted event) {
        broker.convertAndSend("/topic/conversations." + event.message().conversationId(), event.message());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onTransfer(TransferChanged event) {
        broker.convertAndSend("/topic/tenants." + event.requesterTenantId() + ".transfers", event);
        broker.convertAndSend("/topic/tenants." + event.providerTenantId() + ".transfers", event);
    }
}
