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
 * (RLS) and to its own notification topics, the internal control alerts being reserved to
 * holders of {@code CONTROL_VIEW}. Expired tokens are refused on every frame, forcing
 * the client to reconnect with a fresh token. A locked terminal keeps its connection but sends
 * nothing and receives nothing ({@link LockedSessionGuard}) until it is unlocked.
 */
@Component
class StompAuthenticationInterceptor implements ChannelInterceptor {

    static final Pattern CONVERSATION_TOPIC = Pattern.compile("^/topic/conversations\\.([0-9a-f-]{36})$");
    static final Pattern TENANT_TOPIC = Pattern.compile("^/topic/tenants\\.([0-9a-f-]{36})\\.[a-z-]+$");
    static final String CHAT_PERMISSION = "NETWORK_CHAT";
    static final String CONTROL_PERMISSION = "CONTROL_VIEW";
    static final String CONTROL_TOPIC_SUFFIX = ".control-alerts";
    static final String PAYMENTS_TOPIC_SUFFIX = ".payments";
    static final java.util.List<String> PAYMENT_PERMISSIONS = java.util.List.of("SALE_CREATE", "PAYMENT_RECONCILE",
            "CASH_SESSION_MANAGE");
    static final Pattern SEND_DESTINATION = Pattern.compile("^/app/conversations\\.([0-9a-f-]{36})\\.messages$");

    private final BearerTokenAuthenticator authenticator;
    private final ChatService chat;
    private final LockedSessionGuard lockedSessions;

    StompAuthenticationInterceptor(BearerTokenAuthenticator authenticator, ChatService chat,
                                   LockedSessionGuard lockedSessions) {
        this.authenticator = authenticator;
        this.chat = chat;
        this.lockedSessions = lockedSessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            var token = connect(accessor);
            lockedSessions.connected(accessor.getSessionId(), token);
            accessor.setUser(token);
            return message;
        }
        if (command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) {
            return message;
        }
        var token = authenticated(accessor);
        switch (command) {
            case SUBSCRIBE -> authorizeSubscription(token, accessor.getDestination());
            case SEND -> {
                requirePermission(token, CHAT_PERMISSION);
                authorizeSend(accessor.getDestination());
                if (!lockedSessions.open(accessor.getSessionId())) {
                    throw new AccessDeniedException("Session locked");
                }
            }
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
        if (!(token.actor() instanceof Actor.TenantUser user)
                || !(user.can(CHAT_PERMISSION) || user.can(CONTROL_PERMISSION)
                     || PAYMENT_PERMISSIONS.stream().anyMatch(user::can))) {
            throw new AccessDeniedException("NETWORK_CHAT, CONTROL_VIEW or a counter permission required");
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
            // Internal control alerts are for the owner's team, payment confirmations for the counter,
            // the other topics for the network users
            if (destination.endsWith(PAYMENTS_TOPIC_SUFFIX)) {
                if (!(token.actor() instanceof Actor.TenantUser user) || PAYMENT_PERMISSIONS.stream().noneMatch(user::can)) {
                    throw new AccessDeniedException("A counter permission is required for payment confirmations");
                }
                return;
            }
            requirePermission(token, destination.endsWith(CONTROL_TOPIC_SUFFIX) ? CONTROL_PERMISSION : CHAT_PERMISSION);
            return;
        }
        Matcher conversation = CONVERSATION_TOPIC.matcher(destination);
        if (conversation.matches()) {
            requirePermission(token, CHAT_PERMISSION);
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

    private static void requirePermission(PharmacyAuthenticationToken token, String permission) {
        if (!(token.actor() instanceof Actor.TenantUser user) || !user.can(permission)) {
            throw new AccessDeniedException(permission + " permission required");
        }
    }

    private static void authorizeSend(String destination) {
        if (destination == null || !SEND_DESTINATION.matcher(destination).matches()) {
            throw new AccessDeniedException("Sending to " + destination + " is not allowed");
        }
    }
}
