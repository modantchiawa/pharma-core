package com.pharmacore.messaging.web;

import com.pharmacore.messaging.application.ChatService;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.security.BearerTokenAuthenticator;
import com.pharmacore.shared.security.PharmacyAuthenticationToken;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP CONNECT frame with the same multi-issuer JWT logic as HTTP, then
 * authorizes every SUBSCRIBE and SEND: a pharmacy can only listen to conversations it can see
 * (RLS) and to its own notification topic. Expired tokens are refused on every frame, forcing
 * the client to reconnect with a fresh token.
 */
@Component
class StompAuthenticationInterceptor implements ChannelInterceptor {

    static final Pattern CONVERSATION_TOPIC = Pattern.compile("^/topic/conversations\\.([0-9a-f-]{36})$");
    static final Pattern TENANT_TOPIC = Pattern.compile("^/topic/tenants\\.([0-9a-f-]{36})\\.[a-z-]+$");
    static final Pattern SEND_DESTINATION = Pattern.compile("^/app/conversations\\.([0-9a-f-]{36})\\.messages$");

    private final BearerTokenAuthenticator authenticator;
    private final ChatService chat;

    StompAuthenticationInterceptor(BearerTokenAuthenticator authenticator, ChatService chat) {
        this.authenticator = authenticator;
        this.chat = chat;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            accessor.setUser(connect(accessor));
            return message;
        }
        if (command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) {
            return message;
        }
        var token = authenticated(accessor);
        switch (command) {
            case SUBSCRIBE -> authorizeSubscription(token, accessor.getDestination());
            case SEND -> authorizeSend(accessor.getDestination());
            default -> {
                // ACK, NACK, BEGIN, COMMIT, ABORT carry no destination to authorize
            }
        }
        return message;
    }

    private PharmacyAuthenticationToken connect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing bearer token on STOMP CONNECT");
        }
        var token = authenticator.authenticate(header.substring("Bearer ".length()).trim());
        if (!(token.actor() instanceof Actor.TenantUser user) || !user.can("NETWORK_CHAT")) {
            throw new AccessDeniedException("NETWORK_CHAT permission required");
        }
        return token;
    }

    private static PharmacyAuthenticationToken authenticated(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof PharmacyAuthenticationToken token)) {
            throw new AccessDeniedException("Unauthenticated STOMP session");
        }
        Jwt jwt = (Jwt) token.getCredentials();
        if (jwt.getExpiresAt() != null && jwt.getExpiresAt().isBefore(Instant.now())) {
            throw new AccessDeniedException("Token expired, reconnect with a fresh token");
        }
        return token;
    }

    private void authorizeSubscription(PharmacyAuthenticationToken token, String destination) {
        if (destination == null) {
            throw new AccessDeniedException("Missing destination");
        }
        if (destination.startsWith("/user/queue/")) {
            return;
        }
        Matcher tenantTopic = TENANT_TOPIC.matcher(destination);
        if (tenantTopic.matches()) {
            var tenant = token.actor().tenantId().orElseThrow().toString();
            if (!tenant.equals(tenantTopic.group(1))) {
                throw new AccessDeniedException("Cannot subscribe to another pharmacy's notifications");
            }
            return;
        }
        Matcher conversation = CONVERSATION_TOPIC.matcher(destination);
        if (conversation.matches()) {
            UUID conversationId = UUID.fromString(conversation.group(1));
            boolean visible = TenantContext.call(new RequestContext(UUID.randomUUID().toString(), token.actor()),
                    () -> chat.canAccess(conversationId));
            if (!visible) {
                throw new AccessDeniedException("Conversation not accessible");
            }
            return;
        }
        throw new AccessDeniedException("Subscription to " + destination + " is not allowed");
    }

    private static void authorizeSend(String destination) {
        if (destination == null || !SEND_DESTINATION.matcher(destination).matches()) {
            throw new AccessDeniedException("Sending to " + destination + " is not allowed");
        }
    }
}
