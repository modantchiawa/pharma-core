package com.pharmacore.shared.security;

import com.pharmacore.identity.persistence.AppUserEntity;
import com.pharmacore.identity.persistence.AppUserRepository;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.time.Clock;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Loads a pharmacy user with roles and permissions, cached briefly and evicted on any RBAC change. */
@Component
public class TenantUserLoader {

    public static final String ACTOR_CACHE = "actors";

    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public TenantUserLoader(AppUserRepository users, TenantRepository tenants,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.tenants = tenants;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    @Cacheable(cacheNames = ACTOR_CACHE, key = "#tenant.value() + ':' + #subject")
    public Actor.TenantUser load(TenantId tenant, String subject) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("authentication", tenant));
        return TenantContext.call(context, () -> readOnly.execute(status -> {
            var pharmacy = tenants.findById(tenant.value())
                    .orElseThrow(() -> new InvalidBearerTokenException("Unknown pharmacy"));
            if (!pharmacy.isOperational(clock.instant())) {
                throw new DisabledException("Pharmacy " + pharmacy.slug() + " is " + pharmacy.status());
            }
            AppUserEntity user = users.findWithAuthorities(subject)
                    .orElseThrow(() -> new InvalidBearerTokenException("User is not registered in this pharmacy"));
            if (!user.active()) {
                throw new DisabledException("User account is deactivated");
            }
            return toActor(tenant, user);
        }));
    }

    @CacheEvict(cacheNames = ACTOR_CACHE, allEntries = true)
    public void evictAll() {
        // Annotation-driven eviction; called after role, user or tenant status changes.
    }

    private static Actor.TenantUser toActor(TenantId tenant, AppUserEntity user) {
        return new Actor.TenantUser(tenant, user.id(), user.externalSubject(), user.displayName(),
                user.roleCodes(), user.permissionCodes(), user.maxDiscountPercent());
    }
}
