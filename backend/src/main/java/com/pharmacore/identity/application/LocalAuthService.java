package com.pharmacore.identity.application;

import com.nimbusds.jwt.JWTClaimsSet;
import com.pharmacore.identity.application.AuthSessionStore.Kind;
import com.pharmacore.identity.domain.IdlePolicy;
import com.pharmacore.identity.domain.SessionEnd;
import com.pharmacore.identity.domain.SessionLockRules.Status;
import com.pharmacore.identity.persistence.AppUserEntity;
import com.pharmacore.identity.persistence.AppUserRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.shared.security.ActorResolver;
import com.pharmacore.shared.security.SigningKeyService;
import com.pharmacore.shared.security.TenantUserLoader;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authentication that works with no Internet at all: credentials are verified against the
 * local database and tokens are signed with the node's own key.
 * <ul>
 *   <li>password login opens a terminal binding (30 days) and a refresh session;</li>
 *   <li>on a bound terminal, any staff member switches in with a PIN (fast cashier change);</li>
 *   <li>5 failures lock the account for 15 minutes;</li>
 *   <li>each sign-in opens a login session whose lock the server applies: a locked terminal gets
 *       its data back only once the server has checked the owner's PIN or password.</li>
 * </ul>
 */
@Service
public class LocalAuthService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final AuthSessionStore sessions;
    private final SigningKeyService signingKey;
    private final TenantUserLoader loader;
    private final NodeIdentity node;
    private final NodeProperties properties;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final SessionPolicyService sessionPolicy;
    private final LoginSessionService loginSessions;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private final Clock clock;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public LocalAuthService(AppUserRepository users, TenantRepository tenants, AuthSessionStore sessions,
                            SigningKeyService signingKey, TenantUserLoader loader, NodeIdentity node,
                            NodeProperties properties, AuditTrail audit, JdbcClient jdbc,
                            PlatformTransactionManager transactionManager, Clock clock,
                            SessionPolicyService sessionPolicy, LoginSessionService loginSessions) {
        this.users = users;
        this.tenants = tenants;
        this.sessions = sessions;
        this.signingKey = signingKey;
        this.loader = loader;
        this.node = node;
        this.properties = properties;
        this.audit = audit;
        this.jdbc = jdbc;
        this.sessionPolicy = sessionPolicy;
        this.loginSessions = loginSessions;
        this.tx = new TransactionTemplate(transactionManager);
        this.txNew = new TransactionTemplate(transactionManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * @param idlePolicy    the pharmacy's inactivity delays, applied by the terminal even offline
     * @param sessionLocked the session is locked: the access token only reaches what a locked terminal may send
     */
    public record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken, String terminalToken,
                         Actor.TenantUser user, boolean mustChangePassword, IdlePolicy idlePolicy,
                         boolean sessionLocked) {
    }

    /** How the owner of a locked session proves it is them. */
    public enum UnlockMethod {
        PIN,
        PASSWORD
    }

    /** @param pharmacySlug ignored on a hub (single pharmacy), required on the cloud */
    public Tokens login(String pharmacySlug, String email, String password, String terminalId) {
        TenantId tenant = resolveTenant(pharmacySlug);
        return inTenant(tenant, () -> {
            Instant now = clock.instant();
            var user = users.findByEmailIgnoreCase(email.trim())
                    .filter(AppUserEntity::active)
                    .orElseThrow(LocalAuthService::badCredentials);
            if (user.isLocked(now)) {
                throw new BusinessRuleException("account-locked", "Compte verrouillé, réessayez dans quelques minutes");
            }
            if (user.passwordHash() == null || !encoder.matches(password, user.passwordHash())) {
                recordFailure(user.id(), "LOGIN_FAILED", terminalId, now);
                throw badCredentials();
            }
            user.recordSuccess();
            UUID loginSession = loginSessions.open(tenant, user.id(), terminalId, now);
            String terminalToken = sessions.create(tenant, user.id(), Kind.TERMINAL, terminalId,
                    now.plus(properties.terminalTokenTtl()), null);
            String refresh = sessions.create(tenant, user.id(), Kind.REFRESH, terminalId,
                    now.plus(properties.refreshTokenTtl()), loginSession);
            audit.record("LOGIN", "USER", user.id(), Map.of("terminal", terminalId));
            return issue(tenant, user, refresh, terminalToken, now, loginSession, false);
        });
    }

    /** PIN switch on a terminal already bound by a password login. */
    public Tokens pinLogin(String terminalToken, String email, String pin, String terminalId) {
        TenantId tenant = node.hubTenant().orElseGet(() -> tenantOfSession(terminalToken));
        return inTenant(tenant, () -> {
            Instant now = clock.instant();
            var binding = sessions.findActive(terminalToken, now)
                    .filter(s -> s.kind() == Kind.TERMINAL && s.terminalId().equals(terminalId))
                    .orElseThrow(() -> new BusinessRuleException("terminal-not-bound",
                            "Ce poste doit d'abord être ouvert avec un mot de passe"));
            var user = users.findByEmailIgnoreCase(email.trim())
                    .filter(AppUserEntity::active)
                    .orElseThrow(LocalAuthService::badCredentials);
            if (user.isLocked(now)) {
                throw new BusinessRuleException("account-locked", "Compte verrouillé, réessayez dans quelques minutes");
            }
            if (user.pinHash() == null || !encoder.matches(pin, user.pinHash())) {
                recordFailure(user.id(), "PIN_FAILED", terminalId, now);
                throw badCredentials();
            }
            user.recordSuccess();
            UUID loginSession = loginSessions.open(tenant, user.id(), terminalId, now);
            String refresh = sessions.create(tenant, user.id(), Kind.REFRESH, terminalId,
                    now.plus(properties.refreshTokenTtl()), loginSession);
            audit.record("PIN_LOGIN", "USER", user.id(), Map.of("terminal", terminalId,
                    "boundBy", binding.userId().toString()));
            return issue(tenant, user, refresh, terminalToken, now, loginSession, false);
        });
    }

    /**
     * Rotates the refresh token: the previous one is revoked, a reused one is refused. The
     * server's deadlines apply first: past the logout delay the session is closed; locked, the
     * new access token says so and only reaches what a locked terminal may send (its heartbeat
     * keeps the supervision informed, nothing else). Refreshing is not activity.
     */
    public Tokens refresh(String refreshToken) {
        TenantId tenant = node.hubTenant().orElseGet(() -> tenantOfSession(refreshToken));
        return inContext(tenant, () -> {
            Instant now = clock.instant();
            var current = tx.execute(status -> sessions.peek(refreshToken, now))
                    .filter(s -> s.kind() == Kind.REFRESH)
                    .orElseThrow(LocalAuthService::invalidRefresh);
            Status state = current.loginSessionId() == null ? Status.ACTIVE
                    : loginSessions.check(current.loginSessionId(), current.userId(), false);
            if (state == Status.CLOSED) {
                throw new BusinessRuleException("invalid-refresh", "Session fermée après une période d'inactivité");
            }
            return tx.execute(status -> {
                var session = sessions.findActive(refreshToken, now)
                        .filter(s -> s.kind() == Kind.REFRESH)
                        .orElseThrow(LocalAuthService::invalidRefresh);
                var user = users.findById(session.userId()).filter(AppUserEntity::active)
                        .orElseThrow(() -> new BusinessRuleException("invalid-refresh", "Compte désactivé"));
                // A refresh token issued before the server-side sessions gets one now
                UUID loginSession = session.loginSessionId() != null ? session.loginSessionId()
                        : loginSessions.open(tenant, user.id(), session.terminalId(), now);
                sessions.revoke(session.id());
                String next = sessions.create(tenant, user.id(), Kind.REFRESH, session.terminalId(),
                        now.plus(properties.refreshTokenTtl()), loginSession);
                return issue(tenant, user, next, null, now, loginSession, state == Status.LOCKED);
            });
        });
    }

    /**
     * Unlocks the session of a locked terminal once the server has checked the PIN or the
     * password of <b>its owner</b>. Wrong codes count towards the account lockout like any
     * sign-in; the one that locks the account (fifth failure) also closes the session. A success
     * unlocks the session and rotates its refresh token.
     *
     * @throws SessionClosedException when the session is over (logout delay, fifth wrong code)
     */
    public Tokens unlock(String refreshToken, UnlockMethod method, String secret) {
        TenantId tenant = node.hubTenant().orElseGet(() -> sessionTenant(refreshToken)
                .orElseThrow(SessionClosedException::expired));
        return inContext(tenant, () -> {
            Instant now = clock.instant();
            var current = tx.execute(status -> sessions.peek(refreshToken, now))
                    .filter(s -> s.kind() == Kind.REFRESH && s.loginSessionId() != null)
                    .orElseThrow(SessionClosedException::expired);
            if (loginSessions.check(current.loginSessionId(), current.userId(), false) == Status.CLOSED) {
                throw SessionClosedException.idle();
            }
            UnlockOutcome outcome = tx.execute(status -> attemptUnlock(tenant, refreshToken, method, secret, now));
            if (outcome.refusal() != null) {
                throw outcome.refusal();
            }
            return outcome.tokens();
        });
    }

    private record UnlockOutcome(Tokens tokens, RuntimeException refusal) {
    }

    /** Returns its refusal instead of throwing it, so that the counted failure and a closing are committed. */
    private UnlockOutcome attemptUnlock(TenantId tenant, String refreshToken, UnlockMethod method, String secret,
                                        Instant now) {
        var session = sessions.findActive(refreshToken, now).filter(s -> s.kind() == Kind.REFRESH).orElse(null);
        var user = session == null ? null
                : users.findById(session.userId()).filter(AppUserEntity::active).orElse(null);
        if (user == null) {
            return new UnlockOutcome(null, SessionClosedException.expired());
        }
        if (user.isLocked(now)) {
            return new UnlockOutcome(null, new BusinessRuleException("account-locked",
                    "Compte verrouillé, réessayez dans quelques minutes"));
        }
        String hash = method == UnlockMethod.PIN ? user.pinHash() : user.passwordHash();
        if (secret == null || hash == null || !encoder.matches(secret, hash)) {
            user.recordFailure(MAX_ATTEMPTS, LOCK_DURATION, now);
            int attemptsLeft = user.isLocked(now) ? 0 : MAX_ATTEMPTS - user.failedAttempts();
            audit.record("SESSION_UNLOCK_FAILED", "USER", user.id(), Map.of(
                    "method", method.name(),
                    "terminal", session.terminalId(),
                    "attemptsLeft", Integer.toString(attemptsLeft)));
            if (attemptsLeft == 0) {
                loginSessions.close(session.loginSessionId(), SessionEnd.UNLOCK_FAILURES, now);
                audit.record("SESSION_PIN_LOGOUT", "USER", user.id(), Map.of(
                        "trigger", "PIN_FAILURES_LOGOUT",
                        "decidedBy", "SERVER",
                        "terminal", session.terminalId()));
                return new UnlockOutcome(null, SessionClosedException.unlockFailures(MAX_ATTEMPTS));
            }
            return new UnlockOutcome(null, new BusinessRuleException("bad-credentials",
                    method == UnlockMethod.PIN ? "Code PIN incorrect" : "Mot de passe incorrect",
                    Map.of("attemptsLeft", attemptsLeft)));
        }
        user.recordSuccess();
        loginSessions.unlock(session.loginSessionId(), now);
        audit.record("SESSION_UNLOCKED", "USER", user.id(), Map.of(
                "method", method.name(),
                "terminal", session.terminalId()));
        sessions.revoke(session.id());
        String next = sessions.create(tenant, user.id(), Kind.REFRESH, session.terminalId(),
                now.plus(properties.refreshTokenTtl()), session.loginSessionId());
        return new UnlockOutcome(issue(tenant, user, next, null, now, session.loginSessionId(), false), null);
    }

    /** Signs out: the refresh token is revoked and its login session closed. */
    public void logout(String refreshToken) {
        TenantId tenant = node.hubTenant().orElseGet(() -> tenantOfSession(refreshToken));
        inTenant(tenant, () -> {
            Instant now = clock.instant();
            sessions.findActive(refreshToken, now).ifPresent(s -> {
                sessions.revoke(s.id());
                if (s.loginSessionId() != null) {
                    loginSessions.close(s.loginSessionId(), SessionEnd.LOGOUT, now);
                }
            });
            return null;
        });
    }

    public void changePassword(String currentPassword, String newPassword) {
        var actor = TenantContext.requireTenantUser();
        PasswordPolicy.validatePassword(newPassword);
        tx.executeWithoutResult(status -> {
            var user = users.findById(actor.userId()).orElseThrow(LocalAuthService::badCredentials);
            if (user.passwordHash() == null || !encoder.matches(currentPassword, user.passwordHash())) {
                throw badCredentials();
            }
            user.setPassword(encoder.encode(newPassword), false, clock.instant());
            // Open sessions are closed; the terminals this user opened stay bound for PIN switches
            sessions.revokeAllForUser(user.id(), Kind.REFRESH);
            audit.record("PASSWORD_CHANGED", "USER", user.id(), Map.of());
        });
        loader.evictAll();
    }

    public void setPin(String password, String pin) {
        var actor = TenantContext.requireTenantUser();
        PasswordPolicy.validatePin(pin);
        tx.executeWithoutResult(status -> {
            var user = users.findById(actor.userId()).orElseThrow(LocalAuthService::badCredentials);
            if (user.passwordHash() == null || !encoder.matches(password, user.passwordHash())) {
                throw badCredentials();
            }
            user.setPin(encoder.encode(pin));
            audit.record("PIN_CHANGED", "USER", user.id(), Map.of());
        });
    }

    /** Administrator reset: returns a temporary password to hand over to the user. */
    public String resetPassword(UUID userId) {
        String temporary = PasswordPolicy.temporaryPassword();
        tx.executeWithoutResult(status -> {
            var user = users.findById(userId)
                    .orElseThrow(() -> new BusinessRuleException("unknown-user", "Utilisateur inconnu"));
            user.setPassword(encoder.encode(temporary), true, clock.instant());
            sessions.revokeAllForUser(user.id());
            audit.record("PASSWORD_RESET", "USER", user.id(), Map.of("by", TenantContext.actor().auditId()));
        });
        loader.evictAll();
        return temporary;
    }

    /**
     * Supervisor sign-off at the counter: checks a colleague's PIN without opening a session.
     * Failures count towards the lockout exactly like a PIN login.
     *
     * @return the verified user, whose permissions the caller then checks
     */
    public AppUserEntity verifyPin(String email, String pin) {
        Instant now = clock.instant();
        var user = users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .filter(AppUserEntity::active)
                .orElseThrow(LocalAuthService::badCredentials);
        if (user.isLocked(now)) {
            throw new BusinessRuleException("account-locked", "Compte superviseur verrouillé, réessayez dans quelques minutes");
        }
        if (pin == null || user.pinHash() == null || !encoder.matches(pin, user.pinHash())) {
            recordFailure(user.id(), "SUPERVISOR_PIN_FAILED", "approval", now);
            throw badCredentials();
        }
        user.recordSuccess();
        return user;
    }

    /** Used at provisioning: hashes a temporary password for a user being created. */
    public String hashTemporary(String temporaryPassword) {
        return encoder.encode(temporaryPassword);
    }

    public String encode(String secret) {
        return encoder.encode(secret);
    }

    /**
     * Signs a short-lived access token for an authenticated staff member, bound to its login
     * session; a token issued while the session is locked says so.
     */
    public String accessToken(TenantId tenant, AppUserEntity user, Instant now, UUID loginSession, boolean locked) {
        var claims = new JWTClaimsSet.Builder()
                .issuer(signingKey.issuer())
                .subject(user.externalSubject())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .claim(ActorResolver.TENANT_CLAIM, tenant.toString())
                .claim(ActorResolver.TYPE_CLAIM, ActorResolver.TYPE_USER)
                .claim(ActorResolver.SESSION_CLAIM, loginSession.toString())
                .claim("name", user.displayName());
        if (locked) {
            claims.claim(ActorResolver.SESSION_LOCKED_CLAIM, true);
        }
        return signingKey.sign(claims.build());
    }

    private Tokens issue(TenantId tenant, AppUserEntity user, String refresh, String terminalToken, Instant now,
                         UUID loginSession, boolean locked) {
        loader.evictAll();
        var actor = loader.load(tenant, user.externalSubject());
        return new Tokens(accessToken(tenant, user, now, loginSession, locked),
                now.plus(properties.accessTokenTtl()), refresh, terminalToken, actor, user.mustChangePassword(),
                sessionPolicy.current(), locked);
    }

    private TenantId resolveTenant(String pharmacySlug) {
        return node.hubTenant().orElseGet(() -> {
            if (pharmacySlug == null || pharmacySlug.isBlank()) {
                throw new BusinessRuleException("pharmacy-required", "Indiquez l'identifiant de la pharmacie");
            }
            return tx.execute(status -> jdbc.sql("SELECT resolve_active_tenant(:slug)")
                    .param("slug", pharmacySlug.trim().toLowerCase())
                    .query(UUID.class).optional()
                    .map(TenantId::new)
                    .orElseThrow(LocalAuthService::badCredentials));
        });
    }

    /** Cloud mode: the pharmacy of an opaque session is looked up by hash (no tenant context yet). */
    private TenantId tenantOfSession(String rawToken) {
        return sessionTenant(rawToken)
                .orElseThrow(() -> new BusinessRuleException("invalid-session", "Session inconnue"));
    }

    private Optional<TenantId> sessionTenant(String rawToken) {
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("auth-session-lookup"));
        return TenantContext.call(context, () -> tx.execute(status ->
                jdbc.sql("SELECT tenant_id FROM auth_session WHERE token_hash = :hash AND revoked_at IS NULL")
                        .param("hash", AuthSessionStore.hash(rawToken == null ? "" : rawToken))
                        .query(UUID.class).optional()
                        .map(TenantId::new)));
    }

    private <T> T inTenant(TenantId tenant, Supplier<T> action) {
        return inContext(tenant, () -> tx.execute(status -> action.get()));
    }

    /** The pharmacy's context without a transaction, for steps that commit on their own. */
    private <T> T inContext(TenantId tenant, Supplier<T> action) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("local-auth", tenant));
        return TenantContext.call(context, action::get);
    }

    /** Committed independently: the surrounding transaction is rolled back by the thrown exception. */
    private void recordFailure(UUID userId, String event, String terminalId, Instant now) {
        txNew.executeWithoutResult(status -> {
            users.findById(userId).ifPresent(user -> user.recordFailure(MAX_ATTEMPTS, LOCK_DURATION, now));
            audit.record(event, "USER", userId, Map.of("terminal", terminalId));
        });
    }

    private static BusinessRuleException invalidRefresh() {
        return new BusinessRuleException("invalid-refresh", "Session expirée, reconnectez-vous");
    }

    private static BusinessRuleException badCredentials() {
        return new BusinessRuleException("bad-credentials", "Identifiant ou mot de passe incorrect");
    }

    Optional<AppUserEntity> findUser(UUID id) {
        return users.findById(id);
    }
}
