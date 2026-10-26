package com.pharmacore.sales.application;

import com.pharmacore.identity.application.LocalAuthService;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.security.TenantUserLoader;
import com.pharmacore.shared.web.BusinessRuleException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Four eyes" at the counter. Sensitive operations (cancellation above the threshold, cancellation
 * on a closed drawer, Z report with a large difference) need a holder of {@code CASH_APPROVE}:
 * either the actor themself, or a colleague who types their e-mail and PIN on the cashier's
 * terminal. The supervisor's identity is recorded on the operation and in the audit trail.
 */
@Service
public class SupervisorApprovalService {

    public static final String PERMISSION = "CASH_APPROVE";

    /** Credentials typed by the supervisor on the cashier's terminal; both null when none given. */
    public record SupervisorApproval(String email, String pin) {

        public boolean present() {
            return email != null && !email.isBlank() && pin != null && !pin.isBlank();
        }
    }

    private final LocalAuthService auth;
    private final TenantUserLoader loader;
    private final AuditTrail audit;

    public SupervisorApprovalService(LocalAuthService auth, TenantUserLoader loader, AuditTrail audit) {
        this.auth = auth;
        this.loader = loader;
        this.audit = audit;
    }

    /**
     * @param operation audit label of what is being approved
     * @return the approving user: the actor when they hold the permission, else the verified supervisor
     * @throws BusinessRuleException {@code approval-required} when nobody with the permission signed
     */
    public UUID require(SupervisorApproval approval, String operation, String resourceType, UUID resourceId) {
        var actor = TenantContext.requireTenantUser();
        if (actor.can(PERMISSION)) {
            return actor.userId();
        }
        if (approval == null || !approval.present()) {
            throw new BusinessRuleException("approval-required",
                    "Cette opération doit être approuvée par un superviseur (e-mail et code PIN)");
        }
        var supervisor = auth.verifyPin(approval.email(), approval.pin());
        Actor.TenantUser supervisorActor = loader.load(actor.tenant(), supervisor.externalSubject());
        if (!supervisorActor.can(PERMISSION)) {
            throw new BusinessRuleException("approval-not-authorized",
                    supervisor.displayName() + " n'est pas habilité à approuver cette opération");
        }
        if (supervisor.id().equals(actor.userId())) {
            throw new BusinessRuleException("approval-required", "Un autre utilisateur doit approuver l'opération");
        }
        audit.record("SUPERVISOR_APPROVAL", resourceType, resourceId, Map.of(
                "operation", operation,
                "supervisorId", supervisor.id().toString(),
                "requestedBy", actor.userId().toString()));
        return supervisor.id();
    }
}
