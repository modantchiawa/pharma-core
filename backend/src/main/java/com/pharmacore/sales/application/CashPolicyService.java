package com.pharmacore.sales.application;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thresholds above which a supervisor must sign. Zero disables the rule, which is the default
 * of a fresh pharmacy; the owner tightens them from the cash settings.
 */
@Service
public class CashPolicyService {

    public record CashPolicy(long cashDifferenceThresholdCents, long cancelApprovalThresholdCents) {
    }

    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public CashPolicyService(JdbcClient jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public CashPolicy current() {
        return jdbc.sql("""
                        SELECT cash_difference_threshold_cents, cancel_approval_threshold_cents
                        FROM pharmacy_config WHERE tenant_id = :tenant""")
                .param("tenant", TenantContext.requireTenant().value())
                .query((rs, rowNum) -> new CashPolicy(rs.getLong("cash_difference_threshold_cents"),
                        rs.getLong("cancel_approval_threshold_cents")))
                .optional()
                .orElse(new CashPolicy(0, 0));
    }

    @Transactional
    public CashPolicy update(CashPolicy policy) {
        if (policy.cashDifferenceThresholdCents() < 0 || policy.cancelApprovalThresholdCents() < 0) {
            throw new IllegalArgumentException("Thresholds cannot be negative");
        }
        jdbc.sql("""
                        UPDATE pharmacy_config
                        SET cash_difference_threshold_cents = :difference, cancel_approval_threshold_cents = :cancel,
                            updated_at = now()
                        WHERE tenant_id = :tenant""")
                .param("difference", policy.cashDifferenceThresholdCents())
                .param("cancel", policy.cancelApprovalThresholdCents())
                .param("tenant", TenantContext.requireTenant().value())
                .update();
        audit.record("CASH_POLICY_UPDATED", "PHARMACY", TenantContext.requireTenant().value(), Map.of(
                "cashDifferenceThresholdCents", Long.toString(policy.cashDifferenceThresholdCents()),
                "cancelApprovalThresholdCents", Long.toString(policy.cancelApprovalThresholdCents())));
        return policy;
    }
}
