package com.pharmacore.identity.application;

import com.pharmacore.identity.domain.IdlePolicy;
import com.pharmacore.identity.domain.SessionEvent;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inactivity delays of the pharmacy (stored with its configuration, sent with every token so
 * that terminals apply them offline) and the audit of the locks and logouts the terminals
 * perform on their own.
 */
@Service
public class SessionPolicyService {

    private final JdbcClient jdbc;
    private final AuditTrail audit;

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
        audit.record("SESSION_POLICY_UPDATED", "PHARMACY", TenantContext.requireTenant().value(), Map.of(
                "lockAfterMinutes", Integer.toString(lockAfterMinutes),
                "logoutAfterMinutes", Integer.toString(logoutAfterMinutes),
                "previousLockAfterMinutes", Integer.toString(previous.lockAfterMinutes()),
                "previousLogoutAfterMinutes", Integer.toString(previous.logoutAfterMinutes())));
        return new IdlePolicy(lockAfterMinutes, logoutAfterMinutes);
    }

    /** A lock or logout decided by the signed-in user's terminal, journaled under that user. */
    @Transactional
    public void record(SessionEvent event, String terminalId, int idleMinutes) {
        var user = TenantContext.requireTenantUser();
        audit.record(event.auditAction(), "USER", user.userId(), Map.of(
                "trigger", event.name(),
                "terminal", terminalId,
                "idleMinutes", Integer.toString(idleMinutes)));
    }
}
