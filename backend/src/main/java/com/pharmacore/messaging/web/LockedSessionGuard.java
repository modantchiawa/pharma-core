package com.pharmacore.messaging.web;

import com.pharmacore.identity.application.LoginSessionService;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.security.PharmacyAuthenticationToken;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Live notifications (internal control alerts, chat) stop while the terminal is locked: on the
 * way out, a message is dropped when the login session of its STOMP connection is locked or
 * closed, and they flow again as soon as it is unlocked, on the same connection. A connection
 * opened with a token issued while locked, whose session lives on another node, receives nothing
 * until it reconnects with a fresh token.
 */
@Component
class LockedSessionGuard implements ChannelInterceptor {

    /** @param loginSession null when the session is not on this node and the token was issued locked */
    private record Watched(TenantId tenant, UUID loginSession) {
    }

    private final LoginSessionService sessions;
    private final Map<String, Watched> watched = new ConcurrentHashMap<>();

    LockedSessionGuard(LoginSessionService sessions) {
        this.sessions = sessions;
    }

    /** Called on CONNECT, once the token is verified. */
    void connected(String connectionId, PharmacyAuthenticationToken token) {
        if (connectionId == null || !(token.getCredentials() instanceof Jwt jwt)
                || token.actor().tenantId().isEmpty()) {
            return;
        }
        TenantId tenant = token.actor().tenantId().get();
        sessions.sessionOf(jwt).ifPresentOrElse(
                id -> watched.put(connectionId, new Watched(tenant, id)),
                () -> {
                    if (LoginSessionService.issuedLocked(jwt)) {
                        watched.put(connectionId, new Watched(tenant, null));
                    }
                });
    }

    /** Whether the user behind this connection may receive and send right now. */
    boolean open(String connectionId) {
        Watched session = connectionId == null ? null : watched.get(connectionId);
        return session == null
                || session.loginSession() != null && sessions.receiving(session.tenant(), session.loginSession());
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
            return message;
        }
        return open(SimpMessageHeaderAccessor.getSessionId(message.getHeaders())) ? message : null;
    }

    @EventListener
    void disconnected(SessionDisconnectEvent event) {
        watched.remove(event.getSessionId());
    }
}
