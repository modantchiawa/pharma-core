package com.pharmacore.messaging.web;

import com.pharmacore.messaging.application.ChatService;
import com.pharmacore.messaging.application.ChatService.ConversationView;
import com.pharmacore.messaging.application.ChatService.MessageView;
import com.pharmacore.messaging.persistence.ChatMessageEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST side of the messaging: history, conversation management and a fallback for posting. */
@RestController
@RequestMapping("/api/v1/chat")
@PreAuthorize("hasAuthority('NETWORK_CHAT')")
class ChatController {

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/conversations")
    List<ConversationView> conversations() {
        return chat.conversations();
    }

    @PostMapping("/conversations/channels")
    @ResponseStatus(HttpStatus.CREATED)
    ConversationView createChannel(@Valid @RequestBody ChannelRequest request) {
        return chat.createChannel(request.networkId(), request.title());
    }

    @PostMapping("/conversations/direct")
    ConversationView openDirect(@Valid @RequestBody DirectRequest request) {
        return chat.openDirect(request.networkId(), request.tenantId());
    }

    @GetMapping("/conversations/{conversationId}/messages")
    List<MessageView> history(@PathVariable UUID conversationId,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
                              @RequestParam(defaultValue = "50") int limit) {
        return chat.history(conversationId, before, limit);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    MessageView post(@PathVariable UUID conversationId, @Valid @RequestBody MessageRequest request) {
        return chat.post(conversationId, request.kind(), request.body(), request.relatedProductId());
    }

    record ChannelRequest(@NotNull UUID networkId, @NotBlank @Size(max = 150) String title) {
    }

    record DirectRequest(@NotNull UUID networkId, @NotNull UUID tenantId) {
    }

    record MessageRequest(@NotNull ChatMessageEntity.Kind kind, @NotBlank @Size(max = 4000) String body,
                          UUID relatedProductId) {
    }
}
