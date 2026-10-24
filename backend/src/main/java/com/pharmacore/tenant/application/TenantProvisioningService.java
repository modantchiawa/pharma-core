package com.pharmacore.tenant.application;

import com.pharmacore.identity.application.RoleManagementService;
import com.pharmacore.identity.application.UserManagementService;
import com.pharmacore.identity.domain.PredefinedRole;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.security.TenantUserLoader;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity;
import com.pharmacore.tenant.persistence.PharmacyConfigRepository;
import com.pharmacore.tenant.persistence.TenantEntity;
import com.pharmacore.tenant.persistence.TenantRepository;
import com.pharmacore.tenant.persistence.TenantStatus;
import com.pharmacore.tenant.persistence.TenantTopology;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Onboarding of a new pharmacy, executed by a platform operator.
 * <p>
 * Each step runs in its own transaction and its own security context, because the database
 * session variables (RLS) are bound per connection: the platform step writes the tenant row,
 * the tenant step seeds data under the new tenant identity. The tenant stays
 * {@code PROVISIONING} until every step succeeded, and {@link #provision} can be retried.
 */
@Service
public class TenantProvisioningService {

    private final TenantRepository tenants;
    private final PharmacyConfigRepository configs;
    private final RoleManagementService roles;
    private final UserManagementService users;
    private final AuditTrail audit;
    private final TenantUserLoader actorCache;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final JdbcClient jdbc;

    public TenantProvisioningService(TenantRepository tenants, PharmacyConfigRepository configs,
                                     RoleManagementService roles, UserManagementService users, AuditTrail audit,
                                     TenantUserLoader actorCache, PlatformTransactionManager transactionManager,
                                     JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.configs = configs;
        this.roles = roles;
        this.users = users;
        this.audit = audit;
        this.actorCache = actorCache;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public TenantSummary register(NewPharmacy request) {
        if (!(TenantContext.actor() instanceof Actor.PlatformOperator)) {
            throw new BusinessRuleException("platform-only", "Only platform operators can onboard pharmacies");
        }
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            if (tenants.existsBySlug(request.slug())) {
                throw new BusinessRuleException("slug-taken", "Slug " + request.slug() + " is already used");
            }
            tenants.save(new TenantEntity(id, request.slug(), request.legalName(), request.subscriptionPlan(),
                    request.subscriptionExpiresAt(), request.currency(), request.timeZone(), clock.instant()));
            audit.record("TENANT_REGISTERED", "TENANT", id, Map.of(
                    "slug", request.slug(), "plan", request.subscriptionPlan()));
        });
        return provision(id, request.displayName(), request.countryCode(), request.owner());
    }

    /** Idempotent: seeds missing tenant data then activates the tenant. */
    public TenantSummary provision(UUID tenantId, String displayName, String countryCode, Owner owner) {
        var platformContext = TenantContext.require();
        var tenant = new TenantId(tenantId);
        var tenantContext = new RequestContext(platformContext.correlationId(),
                Actor.SystemProcess.forTenant("provisioning:" + platformContext.actor().auditId(), tenant));

        String temporaryPassword = TenantContext.call(tenantContext, () -> tx.execute(status -> {
            if (configs.findCurrent().isPresent()) {
                return null;
            }
            configs.save(new PharmacyConfigEntity(displayName, countryCode, clock.instant()));
            var seeded = roles.seedPredefinedRoles();
            var registration = users.register(owner.email(), owner.displayName(),
                    Set.of(seeded.get(PredefinedRole.TITULAIRE).id()));
            jdbc.sql("SELECT accounting_seed_chart(:tenant)").param("tenant", tenantId).query(Integer.class).single();
            audit.record("TENANT_PROVISIONED", "TENANT", tenantId, Map.of("owner", owner.email()));
            return registration.temporaryPassword();
        }));

        return tx.execute(status -> {
            var entity = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            if (entity.status() == TenantStatus.PROVISIONING) {
                entity.changeStatus(TenantStatus.ACTIVE, clock.instant());
                audit.record("TENANT_ACTIVATED", "TENANT", tenantId, Map.of());
            }
            return TenantSummary.of(entity, temporaryPassword);
        });
    }

    public TenantSummary changeStatus(UUID tenantId, TenantStatus newStatus, String reason) {
        var summary = tx.execute(status -> {
            var entity = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            var previous = entity.status();
            entity.changeStatus(newStatus, clock.instant());
            audit.record("TENANT_STATUS_CHANGED", "TENANT", tenantId, Map.of(
                    "from", previous.name(), "to", newStatus.name(), "reason", reason));
            return TenantSummary.of(entity);
        });
        actorCache.evictAll();
        return summary;
    }

    public TenantSummary renewSubscription(UUID tenantId, String plan, Instant expiresAt) {
        var summary = tx.execute(status -> {
            var entity = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            entity.renewSubscription(plan, expiresAt, clock.instant());
            audit.record("TENANT_SUBSCRIPTION_RENEWED", "TENANT", tenantId, Map.of(
                    "plan", plan, "expiresAt", String.valueOf(expiresAt)));
            return TenantSummary.of(entity);
        });
        actorCache.evictAll();
        return summary;
    }

    public record Owner(String email, String displayName) {
    }

    public record NewPharmacy(String slug,
                              String legalName,
                              String displayName,
                              String countryCode,
                              String currency,
                              String timeZone,
                              String subscriptionPlan,
                              Instant subscriptionExpiresAt,
                              Owner owner) {
    }

    /** @param ownerTemporaryPassword only set by the provisioning call that created the owner account */
    public record TenantSummary(UUID id, String slug, String legalName, TenantStatus status, String subscriptionPlan,
                                Instant subscriptionExpiresAt, TenantTopology topology, String ownerTemporaryPassword) {

        static TenantSummary of(TenantEntity tenant) {
            return of(tenant, null);
        }

        static TenantSummary of(TenantEntity tenant, String ownerTemporaryPassword) {
            return new TenantSummary(tenant.id(), tenant.slug(), tenant.legalName(), tenant.status(),
                    tenant.subscriptionPlan(), tenant.subscriptionExpiresAt(), tenant.topology(), ownerTemporaryPassword);
        }
    }
}
