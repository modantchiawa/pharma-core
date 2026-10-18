package com.pharmacore.shared.tenancy;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Feeds Hibernate's {@code @TenantId} discriminator from the scoped {@link Actor}. Cross-tenant
 * actors resolve to the root identifier (no filter, RLS still enforced by the database).
 * Without context, an identifier matching no row is returned so queries fail closed.
 */
@Component
public class ActorTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    static final UUID ROOT = TenantId.PLATFORM.value();
    static final UUID UNBOUND = new UUID(0L, 1L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current()
                .map(context -> context.actor().tenantId().map(TenantId::value).orElse(ROOT))
                .orElse(UNBOUND);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return ROOT.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
