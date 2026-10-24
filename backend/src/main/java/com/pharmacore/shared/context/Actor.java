package com.pharmacore.shared.context;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who is performing the current unit of work. Sealed so that every consumer
 * (RLS binding, Hibernate tenant resolution, audit) must handle all cases.
 */
public sealed interface Actor {

    /** Tenant the actor is confined to, empty for cross-tenant actors. */
    Optional<TenantId> tenantId();

    /** Platform-wide visibility (bypasses tenant RLS policies). */
    boolean crossTenant();

    String auditType();

    String auditId();

    /** Authenticated pharmacy staff member. */
    record TenantUser(TenantId tenant,
                      UUID userId,
                      String subject,
                      String displayName,
                      Set<String> roles,
                      Set<String> permissions,
                      BigDecimal maxDiscountPercent) implements Actor {

        public TenantUser {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }

        public boolean can(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public Optional<TenantId> tenantId() {
            return Optional.of(tenant);
        }

        @Override
        public boolean crossTenant() {
            return false;
        }

        @Override
        public String auditType() {
            return "USER";
        }

        @Override
        public String auditId() {
            return userId.toString();
        }
    }

    /** Software editor operator (Super-Admin) authenticated on the platform issuer. */
    record PlatformOperator(String subject, String displayName) implements Actor {

        @Override
        public Optional<TenantId> tenantId() {
            return Optional.empty();
        }

        @Override
        public boolean crossTenant() {
            return true;
        }

        @Override
        public String auditType() {
            return "PLATFORM";
        }

        @Override
        public String auditId() {
            return subject;
        }
    }

    /** A paired pharmacy hub synchronizing with the cloud (machine identity, not a person). */
    record HubNode(TenantId tenant, String nodeId) implements Actor {

        @Override
        public Optional<TenantId> tenantId() {
            return Optional.of(tenant);
        }

        @Override
        public boolean crossTenant() {
            return false;
        }

        @Override
        public String auditType() {
            return "HUB";
        }

        @Override
        public String auditId() {
            return nodeId;
        }
    }

    /** Background job or internal process, optionally confined to one tenant. */
    record SystemProcess(String process, TenantId tenant) implements Actor {

        public static SystemProcess crossTenant(String process) {
            return new SystemProcess(process, null);
        }

        public static SystemProcess forTenant(String process, TenantId tenant) {
            return new SystemProcess(process, tenant);
        }

        @Override
        public Optional<TenantId> tenantId() {
            return Optional.ofNullable(tenant);
        }

        @Override
        public boolean crossTenant() {
            return tenant == null;
        }

        @Override
        public String auditType() {
            return "SYSTEM";
        }

        @Override
        public String auditId() {
            return process;
        }
    }
}
