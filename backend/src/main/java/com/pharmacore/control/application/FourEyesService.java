package com.pharmacore.control.application;

import com.pharmacore.control.domain.Amounts;
import com.pharmacore.control.domain.FourEyesPolicy;
import com.pharmacore.control.domain.FourEyesPolicy.Action;
import com.pharmacore.control.domain.FourEyesPolicy.Requirement;
import com.pharmacore.identity.application.LocalAuthService;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.security.TenantUserLoader;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Four eyes at the moment of the action: a sensitive stock exit, refund or cancellation is signed
 * on the requester's own terminal by a second person who types their e-mail and PIN (the same
 * gesture as the supervisor override at the counter, so nobody waits). The approver must hold
 * {@value #PERMISSION} and cannot be the requester; both, the reason and the amount are kept in
 * {@code control_approval} and in the audit trail. Wrong PINs count towards the approver's lockout.
 */
@Service
public class FourEyesService {

    public static final String PERMISSION = "CONTROL_APPROVE";

    /** E-mail and PIN typed by the second person; both null when none given. */
    public record Credentials(String email, String pin) {

        public boolean present() {
            return email != null && !email.isBlank() && pin != null && !pin.isBlank();
        }
    }

    /**
     * @param amountCents value of the operation: stock at cost, refund, or amount paid
     * @param narcotic    a narcotic product is involved: the second person always signs
     */
    public record Request(Action action, String resourceType, UUID resourceId, long amountCents, boolean narcotic,
                          String reason, Credentials approver) {
    }

    /** A recorded second signature. */
    public record Approval(UUID id, UUID requestedBy, Actor.TenantUser approver) {

        public UUID approverId() {
            return approver.userId();
        }
    }

    private final ControlSettingsService settings;
    private final LocalAuthService auth;
    private final TenantUserLoader loader;
    private final TenantRepository tenants;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public FourEyesService(ControlSettingsService settings, LocalAuthService auth, TenantUserLoader loader,
                           TenantRepository tenants, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.settings = settings;
        this.auth = auth;
        this.loader = loader;
        this.tenants = tenants;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Whether the operation needs a second person, without checking anything. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<Requirement> requirement(Action action, long amountCents, boolean narcotic) {
        return FourEyesPolicy.requirement(action, amountCents, narcotic, settings.effective().thresholds());
    }

    /**
     * Checks and records the second signature when the operation needs one.
     *
     * @return the approval, or empty when the requester may act alone
     * @throws BusinessRuleException {@code four-eyes-required}, {@code four-eyes-same-person},
     *                               {@code four-eyes-not-authorized}, {@code bad-credentials}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Approval> check(Request request) {
        var requester = TenantContext.requireTenantUser();
        var requirement = requirement(request.action(), request.amountCents(), request.narcotic());
        if (requirement.isEmpty()) {
            return Optional.empty();
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BusinessRuleException("reason-required", "Le motif est obligatoire");
        }
        if (request.approver() == null || !request.approver().present()) {
            throw new BusinessRuleException("four-eyes-required", message(requirement.get()));
        }
        var verified = auth.verifyPin(request.approver().email(), request.approver().pin());
        if (verified.id().equals(requester.userId())) {
            throw new BusinessRuleException("four-eyes-same-person",
                    "La seconde validation doit venir d'une autre personne que le demandeur");
        }
        Actor.TenantUser approver = loader.load(requester.tenant(), verified.externalSubject());
        if (!approver.can(PERMISSION)) {
            throw new BusinessRuleException("four-eyes-not-authorized",
                    verified.displayName() + " n'est pas habilité(e) à donner la seconde validation");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String reason = request.reason().trim();
        jdbc.sql("""
                        INSERT INTO control_approval (id, tenant_id, action, resource_type, resource_id, amount_cents,
                                                      narcotic, requested_by, approved_by, reason, approved_at)
                        VALUES (:id, :tenant, :action, :resourceType, :resourceId, :amount, :narcotic, :requestedBy,
                                :approvedBy, :reason, :at)""")
                .param("id", id)
                .param("tenant", requester.tenant().value())
                .param("action", request.action().name())
                .param("resourceType", request.resourceType())
                .param("resourceId", request.resourceId())
                .param("amount", Math.abs(request.amountCents()))
                .param("narcotic", request.narcotic())
                .param("requestedBy", requester.userId())
                .param("approvedBy", approver.userId())
                .param("reason", reason.length() <= 255 ? reason : reason.substring(0, 255))
                .param("at", Timestamp.from(now))
                .update();
        audit.record("FOUR_EYES_APPROVED", request.resourceType(), request.resourceId(), Map.of(
                "action", request.action().name(),
                "amountCents", Long.toString(Math.abs(request.amountCents())),
                "narcotic", Boolean.toString(request.narcotic()),
                "requestedBy", requester.userId().toString(),
                "approvedBy", approver.userId().toString(),
                "reason", reason));
        return Optional.of(new Approval(id, requester.userId(), approver));
    }

    private String message(Requirement requirement) {
        String who = " : une autre personne habilitée saisit son e-mail et son code PIN sur ce poste";
        return switch (requirement) {
            case Requirement.Narcotic _ -> "Seconde validation obligatoire pour un stupéfiant" + who;
            case Requirement.AboveThreshold above -> "Seconde validation obligatoire au-delà de "
                    + Amounts.format(above.threshold(), currency()) + who;
        };
    }

    private String currency() {
        return tenants.findById(TenantContext.requireTenant().value()).map(t -> t.currency()).orElse("XOF");
    }
}
