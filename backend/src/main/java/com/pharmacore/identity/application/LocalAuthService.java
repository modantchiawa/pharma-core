package com.pharmacore.identity.application;

import com.nimbusds.jwt.JWTClaimsSet;
import com.pharmacore.identity.application.AuthSessionStore.Kind;
import com.pharmacore.identity.domain.IdlePolicy;
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
 *   <li>5 failures lock the account for 15 minutes.</li>
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
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private final Clock clock;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public LocalAuthService(AppUserRepository users, TenantRepository tenants, AuthSessionStore sessions,
                            SigningKeyService signingKey, TenantUserLoader loader, NodeIdentity node,
                            NodeProperties properties, AuditTrail audit, JdbcClient jdbc,
                            PlatformTransactionManager transactionManager, Clock clock,
                            SessionPolicyService sessionPolicy) {
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
        this.tx = new TransactionTemplate(transactionManager);
        this.txNew = new TransactionTemplate(transactionManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** @param idlePolicy the pharmacy's inactivity delays, applied by the terminal even offline */
    public record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken, String terminalToken,
                         Actor.TenantUser user, boolean mustChangePassword, IdlePolicy idlePolicy) {
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
            String terminalToken = sessions.create(tenant, user.id(), Kind.TERMINAL, terminalId,
                    now.plus(properties.terminalTokenTtl()));
            String refresh = sessions.create(tenant, user.id(), Kind.REFRESH, terminalId,
                    now.plus(properties.refreshTokenTtl()));
            audit.record("LOGIN", "USER", user.id(), Map.of("terminal", terminalId));
            return issue(tenant, user, refresh, terminalToken, now);
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
            String refresh = sessions.create(tenant, user.id(), Kind.REFRESH, terminalId,
                    now.plus(properties.refreshTokenTtl()));
            audit.record("PIN_LOGIN", "USER", user.id(), Map.of("terminal", terminalId,
                    "boundBy", binding.userId().toString()));
            return issue(tenant, user, refresh, terminalToken, now);
        });
    }

    /** Rotates the refresh token: the previous one is revoked, a reused one is refused. */
    public Tokens refresh(String refreshToken) {
        TenantId tenant = node.hubTenant().orElseGet(() -> tenantOfSession(refreshToken));
        return inTenant(tenant, () -> {
            Instant now = clock.instant();
            var session = sessions.findActive(refreshToken, now)
                    .filter(s -> s.kind() == Kind.REFRESH)
                    .orElseThrow(() -> new BusinessRuleException("invalid-refresh", "Session expirée, reconnectez-vous"));
            var user = users.findById(session.userId()).filter(AppUserEntity::active)
                    .orElseThrow(() -> new BusinessRuleException("invalid-refresh", "Compte désactivé"));
            sessions.revoke(session.id());
            String next = sessions.create(tenant, user.id(), Kind.REFRESH, session.terminalId(),
                    now.plus(properties.refreshTokenTtl()));
            return issue(tenant, user, next, null, now);
        });
    }

    public void logout(String refreshToken) {
        TenantId tenant = node.hubTenant().orElseGet(() -> tenantOfSession(refreshToken));
        inTenant(tenant, () -> {
            sessions.findActive(refreshToken, clock.instant()).ifPresent(s -> sessions.revoke(s.id()));
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

    /** Signs a short-lived access token for an authenticated staff member. */
    public String accessToken(TenantId tenant, AppUserEntity user, Instant now) {
        return signingKey.sign(new JWTClaimsSet.Builder()
                .issuer(signingKey.issuer())
                .subject(user.externalSubject())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .claim(ActorResolver.TENANT_CLAIM, tenant.toString())
                .claim(ActorResolver.TYPE_CLAIM, ActorResolver.TYPE_USER)
                .claim("name", user.displayName())
                .build());
    }

    private Tokens issue(TenantId tenant, AppUserEntity user, String refresh, String terminalToken, Instant now) {
        loader.evictAll();
        var actor = loader.load(tenant, user.externalSubject());
        return new Tokens(accessToken(tenant, user, now), now.plus(properties.accessTokenTtl()), refresh,
                terminalToken, actor, user.mustChangePassword(), sessionPolicy.current());
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
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("auth-session-lookup"));
        return TenantContext.call(context, () -> tx.execute(status ->
                jdbc.sql("SELECT tenant_id FROM auth_session WHERE token_hash = :hash AND revoked_at IS NULL")
                        .param("hash", AuthSessionStore.hash(rawToken == null ? "" : rawToken))
                        .query(UUID.class).optional()
                        .map(TenantId::new)
                        .orElseThrow(() -> new BusinessRuleException("invalid-session", "Session inconnue"))));
    }

    private <T> T inTenant(TenantId tenant, Supplier<T> action) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("local-auth", tenant));
        return TenantContext.call(context, () -> tx.execute(status -> action.get()));
    }

    /** Committed independently: the surrounding transaction is rolled back by the thrown exception. */
    private void recordFailure(UUID userId, String event, String terminalId, Instant now) {
        txNew.executeWithoutResult(status -> {
            users.findById(userId).ifPresent(user -> user.recordFailure(MAX_ATTEMPTS, LOCK_DURATION, now));
            audit.record(event, "USER", userId, Map.of("terminal", terminalId));
        });
    }

    private static BusinessRuleException badCredentials() {
        return new BusinessRuleException("bad-credentials", "Identifiant ou mot de passe incorrect");
    }

    Optional<AppUserEntity> findUser(UUID id) {
        return users.findById(id);
    }
}
