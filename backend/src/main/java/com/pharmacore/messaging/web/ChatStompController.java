package com.pharmacore.messaging.web;

import com.pharmacore.messaging.application.ChatService;
import com.pharmacore.messaging.persistence.ChatMessageEntity;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.security.PharmacyAuthenticationToken;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

/** STOMP entry point; the broadcast happens after commit (see {@link MessagingEventRelay}). */
@Controller
class ChatStompController {

    private final ChatService chat;

    ChatStompController(ChatService chat) {
        this.chat = chat;
    }

    @MessageMapping("/conversations.{conversationId}.messages")
    void post(@DestinationVariable UUID conversationId, @Payload @Validated OutgoingMessage message,
              Principal principal) {
        if (!(principal instanceof PharmacyAuthenticationToken token)) {
            throw new AccessDeniedException("Unauthenticated");
        }
        var context = new RequestContext(UUID.randomUUID().toString(), token.actor());
        TenantContext.run(context, () -> chat.post(conversationId, message.kind(), message.body(),
                message.relatedProductId()));
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    String handle(RuntimeException e) {
        return e.getMessage();
    }

    record OutgoingMessage(@NotNull ChatMessageEntity.Kind kind,
                           @NotBlank @Size(max = 4000) String body,
                           UUID relatedProductId) {
    }
}
