package com.pharmacore.identity.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pharmacore.identity.domain.LockReason;
import com.pharmacore.identity.domain.LoginSessionState;
import com.pharmacore.identity.domain.SessionEnd;
import com.pharmacore.identity.domain.SessionLockRules;
import com.pharmacore.identity.domain.SessionLockRules.Status;
import com.pharmacore.identity.domain.SessionLockRules.Verdict;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.security.ActorResolver;
import com.pharmacore.shared.security.PharmacyAuthenticationToken;
import com.pharmacore.shared.security.SigningKeyService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Server-side state of the login sessions opened on this node: last activity and lock, applied
 * to every request of the staff ({@code SessionLockFilter}) and to the live notifications. The
 * state lives in {@code login_session}, which is authoritative (instances of a cloud agree
 * through it), behind a memory cache: an entry is read again after {@link #REVALIDATE_AFTER},
 * activity is written at most every {@link SessionLockRules#PERSIST_INTERVAL}, a lock, an unlock
 * or a closing at once.
 */
@Service
public class LoginSessionService {

    static final Duration REVALIDATE_AFTER = Duration.ofSeconds(5);

    private final JdbcClient jdbc;
    private final SessionPolicyService policies;
    private final SigningKeyService signingKey;
    private final AuditTrail audit;
    private final Clock clock;
    private final TransactionTemplate txNew;
    private final Cache<UUID, Tracked> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1)).maximumSize(100_000).build();

    public LoginSessionService(JdbcClient jdbc, SessionPolicyService policies, SigningKeyService signingKey,
                               AuditTrail audit, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.signingKey = signingKey;
        this.audit = audit;
        this.clock = clock;
        this.txNew = new TransactionTemplate(transactionManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A session as this instance last saw it, with the activity it has not written yet. */
    private static final class Tracked {
        final ReentrantLock lock = new ReentrantLock();
        LoginSessionState persisted;
        Instant readAt;
        Instant observed;

        LoginSessionState effective() {
            return persisted == null || observed == null ? persisted : persisted.withActivity(observed);
        }
    }

    // ------------------------------------------------------------------ tokens

    /**
     * The login session of a token signed by this node; empty for a token of another issuer (a
     * hub's token seen by the cloud, a federated identity provider), whose session is not here.
     */
    public Optional<UUID> sessionOf(Jwt jwt) {
        if (!signingKey.issuer().equals(jwt.getClaimAsString("iss"))) {
            return Optional.empty();
        }
        String sid = jwt.getClaimAsString(ActorResolver.SESSION_CLAIM);
        try {
            return sid == null ? Optional.empty() : Optional.of(UUID.fromString(sid));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Whether a token signed by this node predates the server-side sessions (no sid). */
    public boolean withoutSession(Jwt jwt) {
        return signingKey.issuer().equals(jwt.getClaimAsString("iss"))
                && jwt.getClaimAsString(ActorResolver.SESSION_CLAIM) == null;
    }

    /** A token issued while its session was locked only reaches what a locked terminal may send. */
    public static boolean issuedLocked(Jwt jwt) {
        return Boolean.TRUE.equals(jwt.getClaimAsBoolean(ActorResolver.SESSION_LOCKED_CLAIM));
    }

    private Optional<UUID> currentSession() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof PharmacyAuthenticationToken token
                && token.getCredentials() instanceof Jwt jwt ? sessionOf(jwt) : Optional.empty();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Opens the session of a sign-in (password or PIN); its refresh tokens are linked to it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID open(TenantId tenant, UUID userId, String terminalId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO login_session (id, tenant_id, user_id, terminal_id, started_at, last_activity_at)
                        VALUES (:id, :tenant, :user, :terminal, :now, :now)""")
                .param("id", id)
                .param("tenant", tenant.value())
                .param("user", userId)
                .param("terminal", terminalId)
                .param("now", Timestamp.from(now))
                .update();
        return id;
    }

    /**
     * Applies the pharmacy's deadlines to a request of the session's owner, in the request's
     * tenant context: a session past its lock delay is locked, past its logout delay closed (its
     * tokens revoked), both audited as decided by the server. The user's activity is recorded
     * only while the session is open and unlocked.
     */
    public Status check(UUID sessionId, UUID userId, boolean activity) {
        Instant now = clock.instant();
        Tracked entry = cache.get(sessionId, id -> new Tracked());
        entry.lock.lock();
        try {
            revalidate(entry, sessionId, now);
            LoginSessionState state = entry.effective();
            if (state != null && !state.userId().equals(userId)) {
                return Status.CLOSED;
            }
            Verdict verdict = SessionLockRules.evaluate(state, policies.cached(), now);
            if (verdict.changed()) {
                applyServerDecision(entry, sessionId, state, verdict, now);
            } else if (verdict.status() == Status.ACTIVE && activity) {
                recordActivity(entry, sessionId, now);
            }
            return verdict.status();
        } finally {
            entry.lock.unlock();
        }
    }

    /** Whether the session may receive live notifications (open and unlocked); changes nothing. */
    public boolean receiving(TenantId tenant, UUID sessionId) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("live-session", tenant));
        return TenantContext.call(context, () -> {
            Instant now = clock.instant();
            Tracked entry = cache.get(sessionId, id -> new Tracked());
            entry.lock.lock();
            try {
                revalidate(entry, sessionId, now);
                return SessionLockRules.evaluate(entry.effective(), policies.cached(), now).status() == Status.ACTIVE;
            } finally {
                entry.lock.unlock();
            }
        });
    }

    /**
     * The terminal of the signed-in user locks its session (padlock, Ctrl+L, its own inactivity
     * timer): from now on the server refuses its requests until the PIN or password is checked.
     * Journaled once, however many tabs report it.
     */
    @Transactional
    public void lockCurrent(LockReason reason, String terminalId, int idleMinutes) {
        var user = TenantContext.requireTenantUser();
        Optional<UUID> session = currentSession();
        boolean locked = session.flatMap(id -> lockRow(id, clock.instant(), reason)).isPresent();
        if (session.isEmpty() || locked) {
            audit.record("SESSION_LOCKED", "USER", user.userId(), Map.of(
                    "trigger", reason == LockReason.MANUAL ? "MANUAL_LOCK" : "IDLE_LOCK",
                    "decidedBy", "TERMINAL",
                    "terminal", terminalId,
                    "idleMinutes", Integer.toString(idleMinutes)));
        }
        session.ifPresent(this::forgetAfterCommit);
    }

    /** Unlocked once the server has verified the owner's PIN or password; in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unlock(UUID sessionId, Instant now) {
        jdbc.sql("""
                        UPDATE login_session SET locked_at = NULL, lock_reason = NULL, last_activity_at = :now
                        WHERE id = :id AND ended_at IS NULL""")
                .param("id", sessionId)
                .param("now", Timestamp.from(now))
                .update();
        forgetAfterCommit(sessionId);
    }

    /**
     * Closes a session and revokes its refresh tokens, in the caller's transaction.
     *
     * @return the terminal of the session, empty when it was already closed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> close(UUID sessionId, SessionEnd reason, Instant now) {
        Optional<String> terminal = jdbc.sql("""
                        UPDATE login_session SET ended_at = :now, end_reason = :reason
                        WHERE id = :id AND ended_at IS NULL
                        RETURNING terminal_id""")
                .param("id", sessionId)
                .param("now", Timestamp.from(now))
                .param("reason", reason.name())
                .query(String.class)
                .optional();
        jdbc.sql("UPDATE auth_session SET revoked_at = now() WHERE login_session_id = :id AND revoked_at IS NULL")
                .param("id", sessionId)
                .update();
        forgetAfterCommit(sessionId);
        return terminal;
    }

    /** Sessions closed for a week, or left without any token, are deleted (housekeeping). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int purge(Instant now) {
        return jdbc.sql("""
                        DELETE FROM login_session ls
                        WHERE ls.last_activity_at < :cutoff
                          AND (ls.ended_at IS NOT NULL
                               OR NOT EXISTS (SELECT 1 FROM auth_session a WHERE a.login_session_id = ls.id))""")
                .param("cutoff", Timestamp.from(now.minus(Duration.ofDays(7))))
                .update();
    }

    // ------------------------------------------------------------------ internals

    private void revalidate(Tracked entry, UUID sessionId, Instant now) {
        if (entry.readAt == null || !now.isBefore(entry.readAt.plus(REVALIDATE_AFTER))) {
            entry.persisted = txNew.execute(status -> read(sessionId));
            entry.readAt = now;
        }
    }

    private LoginSessionState read(UUID sessionId) {
        return jdbc.sql("""
                        SELECT ls.user_id, ls.last_activity_at, ls.locked_at, ls.lock_reason,
                               ls.ended_at IS NOT NULL
                               OR NOT EXISTS (SELECT 1 FROM auth_session a
                                              WHERE a.login_session_id = ls.id AND a.kind = 'REFRESH'
                                                AND a.revoked_at IS NULL AND a.expires_at > now()) AS ended
                        FROM login_session ls WHERE ls.id = :id""")
                .param("id", sessionId)
                .query((rs, rowNum) -> new LoginSessionState(
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("last_activity_at").toInstant(),
                        rs.getTimestamp("locked_at") == null ? null : rs.getTimestamp("locked_at").toInstant(),
                        rs.getString("lock_reason") == null ? null : LockReason.valueOf(rs.getString("lock_reason")),
                        rs.getBoolean("ended")))
                .optional()
                .orElse(null);
    }

    private void recordActivity(Tracked entry, UUID sessionId, Instant now) {
        entry.observed = now;
        if (!SessionLockRules.persistDue(entry.persisted.lastActivityAt(), now)) {
            return;
        }
        Integer written = txNew.execute(status -> jdbc.sql("""
                        UPDATE login_session SET last_activity_at = GREATEST(last_activity_at, :at)
                        WHERE id = :id AND locked_at IS NULL AND ended_at IS NULL""")
                .param("id", sessionId)
                .param("at", Timestamp.from(now))
                .update());
        if (written != null && written == 1) {
            entry.persisted = entry.persisted.withActivity(now);
            entry.observed = null;
        } else {
            entry.readAt = null;
        }
    }

    /** The server reached a deadline before the terminal said anything: it locks or closes itself. */
    private void applyServerDecision(Tracked entry, UUID sessionId, LoginSessionState state, Verdict verdict,
                                     Instant now) {
        String idle = Integer.toString(SessionLockRules.idleMinutes(state, now));
        if (verdict.status() == Status.LOCKED) {
            txNew.executeWithoutResult(status -> lockRow(sessionId, verdict.lockedAt(), LockReason.SERVER)
                    .ifPresent(terminal -> audit.record("SESSION_LOCKED", "USER", state.userId(), Map.of(
                            "trigger", "SERVER_LOCK",
                            "decidedBy", "SERVER",
                            "terminal", terminal,
                            "idleMinutes", idle))));
            entry.persisted = state.lockedAt(verdict.lockedAt(), LockReason.SERVER);
        } else {
            txNew.executeWithoutResult(status -> close(sessionId, SessionEnd.IDLE_LOGOUT, now)
                    .ifPresent(terminal -> audit.record("SESSION_IDLE_LOGOUT", "USER", state.userId(), Map.of(
                            "trigger", "IDLE_LOGOUT",
                            "decidedBy", "SERVER",
                            "terminal", terminal,
                            "idleMinutes", idle))));
            entry.persisted = state.closed();
        }
        entry.observed = null;
        entry.readAt = now;
    }

    /** @return the session's terminal when this call locked it, empty when it was already locked or closed */
    private Optional<String> lockRow(UUID sessionId, Instant at, LockReason reason) {
        return jdbc.sql("""
                        UPDATE login_session SET locked_at = :at, lock_reason = :reason
                        WHERE id = :id AND locked_at IS NULL AND ended_at IS NULL
                        RETURNING terminal_id""")
                .param("id", sessionId)
                .param("at", Timestamp.from(at))
                .param("reason", reason.name())
                .query(String.class)
                .optional();
    }

    /** Other requests read the new state from the database once it is committed. */
    private void forgetAfterCommit(UUID sessionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate(sessionId);
                }
            });
        } else {
            cache.invalidate(sessionId);
        }
    }
}
