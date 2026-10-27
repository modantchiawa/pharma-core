package com.pharmacore.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.identity.application.LoginSessionService;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.security.ActorResolver;
import com.pharmacore.shared.security.PharmacyAuthenticationToken;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;

class LockedSessionGuardTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID LOGIN_SESSION = UUID.randomUUID();

    /** Login sessions of this node, one of them, whose lock the test switches. */
    private static final class FakeSessions extends LoginSessionService {
        boolean locked;
        final String ownIssuer;

        FakeSessions(String ownIssuer) {
            super(null, null, null, null, Clock.systemUTC(), null);
            this.ownIssuer = ownIssuer;
        }

        @Override
        public Optional<UUID> sessionOf(Jwt jwt) {
            return ownIssuer.equals(jwt.getClaimAsString("iss")) ? Optional.of(LOGIN_SESSION) : Optional.empty();
        }

        @Override
        public boolean receiving(TenantId tenant, UUID sessionId) {
            return tenant.equals(TENANT) && sessionId.equals(LOGIN_SESSION) && !locked;
        }
    }

    private static PharmacyAuthenticationToken token(String issuer, boolean issuedLocked) {
        var jwt = Jwt.withTokenValue("t").header("alg", "RS256").claim("iss", issuer).subject("u")
                .claim(ActorResolver.SESSION_LOCKED_CLAIM, issuedLocked).build();
        var user = new Actor.TenantUser(TENANT, UUID.randomUUID(), "u", "Caissier", Set.of("CAISSIER"),
                Set.of("CONTROL_VIEW"), BigDecimal.ZERO);
        return new PharmacyAuthenticationToken(jwt, user, List.of());
    }

    private static Message<byte[]> frame(SimpMessageType type, String connection) {
        var headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId(connection);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test
    void messagesStopWhileTheSessionIsLockedAndResumeOnTheSameConnection() {
        var sessions = new FakeSessions("pharmacore:hub");
        var guard = new LockedSessionGuard(sessions);
        guard.connected("c1", token("pharmacore:hub", false));

        assertThat(guard.preSend(frame(SimpMessageType.MESSAGE, "c1"), null)).isNotNull();
        sessions.locked = true;
        assertThat(guard.preSend(frame(SimpMessageType.MESSAGE, "c1"), null)).isNull();
        assertThat(guard.open("c1")).isFalse();
        // Heartbeats and connection frames still go out: the connection stays up
        assertThat(guard.preSend(frame(SimpMessageType.HEARTBEAT, "c1"), null)).isNotNull();
        assertThat(guard.preSend(frame(SimpMessageType.CONNECT_ACK, "c1"), null)).isNotNull();
        sessions.locked = false;
        assertThat(guard.preSend(frame(SimpMessageType.MESSAGE, "c1"), null)).isNotNull();
    }

    @Test
    void aTokenIssuedLockedElsewhereReceivesNothingAndOtherConnectionsAreUntouched() {
        var sessions = new FakeSessions("pharmacore:cloud");
        var guard = new LockedSessionGuard(sessions);
        guard.connected("hub-token", token("pharmacore:hub", true));
        guard.connected("federated", token("https://idp.example/realms/pharmacies", false));

        assertThat(guard.preSend(frame(SimpMessageType.MESSAGE, "hub-token"), null)).isNull();
        assertThat(guard.preSend(frame(SimpMessageType.MESSAGE, "federated"), null)).isNotNull();
        assertThat(guard.preSend(frame(SimpMessageType.MESSAGE, "unknown"), null)).isNotNull();
    }
}
