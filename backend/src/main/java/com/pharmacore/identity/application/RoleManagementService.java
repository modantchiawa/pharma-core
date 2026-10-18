package com.pharmacore.identity.application;

import com.pharmacore.identity.domain.Permission;
import com.pharmacore.identity.domain.PredefinedRole;
import com.pharmacore.identity.persistence.RoleEntity;
import com.pharmacore.identity.persistence.RoleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.security.TenantUserLoader;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customization of the permission matrix by the pharmacy owner. */
@Service
public class RoleManagementService {

    /** The owner role can never lose the ability to administer the pharmacy (lock-out protection). */
    private static final Set<Permission> OWNER_LOCKED = EnumSet.of(Permission.ROLE_MANAGE, Permission.USER_MANAGE);

    private final RoleRepository roles;
    private final AuditTrail audit;
    private final TenantUserLoader actorCache;

    public RoleManagementService(RoleRepository roles, AuditTrail audit, TenantUserLoader actorCache) {
        this.roles = roles;
        this.audit = audit;
        this.actorCache = actorCache;
    }

    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return roles.findAllWithPermissions().stream().map(RoleView::of).toList();
    }

    /** Seeds the predefined roles of a freshly provisioned pharmacy (current tenant context). */
    @Transactional
    public Map<PredefinedRole, RoleEntity> seedPredefinedRoles() {
        return EnumSet.allOf(PredefinedRole.class).stream()
                .collect(Collectors.toMap(role -> role, role -> roles.save(new RoleEntity(
                        role.name(), role.label(), true, role.maxDiscountPercent(), role.permissions()))));
    }

    @Transactional
    public RoleView create(RoleCommand command) {
        if (roles.existsByCode(command.code())) {
            throw new BusinessRuleException("role-code-taken", "Role code " + command.code() + " already exists");
        }
        var role = roles.save(new RoleEntity(command.code(), command.name(), false, command.maxDiscountPercent(),
                command.permissions()));
        audit.record("ROLE_CREATED", "ROLE", role.id(), describe(command));
        return RoleView.of(role);
    }

    @Transactional
    public RoleView update(UUID roleId, RoleCommand command) {
        var role = roles.findWithPermissions(roleId).orElseThrow(() -> new NotFoundException("Role", roleId));
        if (PredefinedRole.TITULAIRE.name().equals(role.code()) && !command.permissions().containsAll(OWNER_LOCKED)) {
            throw new BusinessRuleException("owner-lockout",
                    "The owner role must keep " + OWNER_LOCKED + " to prevent administrative lock-out");
        }
        var before = role.permissions();
        role.redefine(command.name(), command.maxDiscountPercent(), command.permissions());
        var details = describe(command);
        details.put("previousPermissions", before.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        audit.record("ROLE_UPDATED", "ROLE", role.id(), details);
        actorCache.evictAll();
        return RoleView.of(role);
    }

    @Transactional
    public void delete(UUID roleId) {
        var role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("Role", roleId));
        if (role.systemRole()) {
            throw new BusinessRuleException("system-role", "Predefined roles cannot be deleted");
        }
        roles.delete(role);
        audit.record("ROLE_DELETED", "ROLE", roleId, Map.of("code", role.code()));
        actorCache.evictAll();
    }

    private static Map<String, String> describe(RoleCommand command) {
        return new java.util.HashMap<>(Map.of(
                "code", command.code(),
                "name", command.name(),
                "maxDiscountPercent", command.maxDiscountPercent().toPlainString(),
                "permissions", command.permissions().stream().map(Enum::name).sorted().collect(Collectors.joining(","))));
    }

    public record RoleCommand(String code, String name, BigDecimal maxDiscountPercent, Set<Permission> permissions) {
    }

    public record RoleView(UUID id, String code, String name, boolean systemRole, BigDecimal maxDiscountPercent,
                           Set<Permission> permissions, long version) {

        static RoleView of(RoleEntity role) {
            return new RoleView(role.id(), role.code(), role.name(), role.systemRole(), role.maxDiscountPercent(),
                    role.permissions(), role.version());
        }
    }
}
