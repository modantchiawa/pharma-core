package com.pharmacore.identity.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pharmacore.identity.domain.IdlePolicy;
import com.pharmacore.identity.domain.SessionEvent;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Inactivity delays of the pharmacy (stored with its configuration, sent with every token so
 * that terminals apply them offline) and the audit of the locks and logouts the terminals
 * perform on their own.
 */
@Service
public class SessionPolicyService {

    private final JdbcClient jdbc;
    private final AuditTrail audit;
    /** Read on every request of the staff (server-side lock); another instance sees a change within 30 s. */
    private final Cache<UUID, IdlePolicy> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(100_000).build();

    public SessionPolicyService(JdbcClient jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public IdlePolicy current() {
        return jdbc.sql("SELECT idle_lock_minutes, idle_logout_minutes FROM pharmacy_config WHERE tenant_id = :tenant")
                .param("tenant", TenantContext.requireTenant().value())
                .query((rs, rowNum) -> IdlePolicy.stored(
                        rs.getObject("idle_lock_minutes") == null ? null : rs.getInt("idle_lock_minutes"),
                        rs.getObject("idle_logout_minutes") == null ? null : rs.getInt("idle_logout_minutes")))
                .optional()
                .orElse(IdlePolicy.DEFAULT);
    }

    /** The delays of the pharmacy of the current context, from a short-lived cache. */
    public IdlePolicy cached() {
        return cache.get(TenantContext.requireTenant().value(), tenant -> current());
    }

    @Transactional
    public IdlePolicy update(int lockAfterMinutes, int logoutAfterMinutes) {
        IdlePolicy.problem(lockAfterMinutes, logoutAfterMinutes).ifPresent(problem -> {
            throw new BusinessRuleException("session-policy", problem);
        });
        IdlePolicy previous = current();
        jdbc.sql("""
                        UPDATE pharmacy_config
                        SET idle_lock_minutes = :lock, idle_logout_minutes = :logout, updated_at = now()
                        WHERE tenant_id = :tenant""")
                .param("lock", lockAfterMinutes)
                .param("logout", logoutAfterMinutes)
                .param("tenant", TenantContext.requireTenant().value())
                .update();
        UUID tenant = TenantContext.requireTenant().value();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.invalidate(tenant);
            }
        });
        audit.record("SESSION_POLICY_UPDATED", "PHARMACY", TenantContext.requireTenant().value(), Map.of(
                "lockAfterMinutes", Integer.toString(lockAfterMinutes),
                "logoutAfterMinutes", Integer.toString(logoutAfterMinutes),
                "previousLockAfterMinutes", Integer.toString(previous.lockAfterMinutes()),
                "previousLogoutAfterMinutes", Integer.toString(previous.logoutAfterMinutes())));
        return new IdlePolicy(lockAfterMinutes, logoutAfterMinutes);
    }

    /** A logout decided by the signed-in user's terminal, journaled under that user. */
    @Transactional
    public void record(SessionEvent event, String terminalId, int idleMinutes) {
        var user = TenantContext.requireTenantUser();
        audit.record(event.auditAction(), "USER", user.userId(), Map.of(
                "trigger", event.name(),
                "terminal", terminalId,
                "idleMinutes", Integer.toString(idleMinutes)));
    }
}
