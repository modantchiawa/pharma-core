package com.pharmacore.identity.application;

import com.pharmacore.shared.context.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque long-lived tokens (refresh and terminal binding). Only their SHA-256 is stored, so a
 * database read never yields a usable token.
 */
@Repository
public class AuthSessionStore {

    public enum Kind {
        REFRESH,
        TERMINAL
    }

    public record Session(UUID id, UUID userId, Kind kind, String terminalId, Instant expiresAt) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public AuthSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a session and returns the raw token (shown once). */
    @Transactional(propagation = Propagation.MANDATORY)
    public String create(TenantId tenant, UUID userId, Kind kind, String terminalId, Instant expiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = kind.name().toLowerCase() + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO auth_session (id, tenant_id, user_id, kind, token_hash, terminal_id, created_at, expires_at)
                        VALUES (:id, :tenant, :user, :kind, :hash, :terminal, now(), :expires)""")
                .param("id", UUID.randomUUID())
                .param("tenant", tenant.value())
                .param("user", userId)
                .param("kind", kind.name())
                .param("hash", hash(raw))
                .param("terminal", terminalId)
                .param("expires", Timestamp.from(expiresAt))
                .update();
        return raw;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Session> findActive(String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE auth_session SET last_used_at = now()
                        WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > :now
                        RETURNING id, user_id, kind, terminal_id, expires_at""")
                .param("hash", hash(rawToken))
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new Session(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        Kind.valueOf(rs.getString("kind")), rs.getString("terminal_id"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(UUID sessionId) {
        jdbc.sql("UPDATE auth_session SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", sessionId).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAllForUser(UUID userId) {
        return jdbc.sql("UPDATE auth_session SET revoked_at = now() WHERE user_id = :user AND revoked_at IS NULL")
                .param("user", userId).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAllForUser(UUID userId, Kind kind) {
        return jdbc.sql("""
                        UPDATE auth_session SET revoked_at = now()
                        WHERE user_id = :user AND kind = :kind AND revoked_at IS NULL""")
                .param("user", userId).param("kind", kind.name()).update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeExpired(Instant now) {
        return jdbc.sql("DELETE FROM auth_session WHERE expires_at < :now OR revoked_at < :cutoff")
                .param("now", Timestamp.from(now))
                .param("cutoff", Timestamp.from(now.minusSeconds(7 * 24 * 3600)))
                .update();
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
