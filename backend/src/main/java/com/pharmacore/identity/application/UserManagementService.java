package com.pharmacore.identity.application;

import com.pharmacore.identity.persistence.AppUserEntity;
import com.pharmacore.identity.persistence.AppUserRepository;
import com.pharmacore.identity.persistence.RoleEntity;
import com.pharmacore.identity.persistence.RoleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.security.TenantUserLoader;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final AuditTrail audit;
    private final TenantUserLoader actorCache;
    private final Clock clock;

    public UserManagementService(AppUserRepository users, RoleRepository roles, AuditTrail audit,
                                 TenantUserLoader actorCache, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.audit = audit;
        this.actorCache = actorCache;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAllWithRoles().stream().map(UserView::of).toList();
    }

    @Transactional
    public UserView register(String externalSubject, String email, String displayName, Set<UUID> roleIds) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw new BusinessRuleException("email-taken", "A user with this e-mail already exists");
        }
        var user = new AppUserEntity(externalSubject, email.toLowerCase(), displayName, clock.instant());
        user.assignRoles(resolveRoles(roleIds));
        users.save(user);
        audit.record("USER_REGISTERED", "USER", user.id(), Map.of(
                "email", user.email(), "roles", codes(user.roles())));
        return UserView.of(user);
    }

    @Transactional
    public UserView changeRoles(UUID userId, Set<UUID> roleIds) {
        var user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        String before = codes(user.roles());
        user.assignRoles(resolveRoles(roleIds));
        audit.record("USER_ROLES_CHANGED", "USER", userId, Map.of("before", before, "after", codes(user.roles())));
        actorCache.evictAll();
        return UserView.of(user);
    }

    @Transactional
    public UserView setActive(UUID userId, boolean active) {
        var user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        if (!active && user.id().equals(TenantContext.requireTenantUser().userId())) {
            throw new BusinessRuleException("self-deactivation", "You cannot deactivate your own account");
        }
        if (active) {
            user.activate();
        } else {
            user.deactivate();
        }
        audit.record(active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "USER", userId, Map.of());
        actorCache.evictAll();
        return UserView.of(user);
    }

    private Set<RoleEntity> resolveRoles(Set<UUID> roleIds) {
        var found = new HashSet<>(roles.findByIdIn(roleIds));
        if (found.size() != roleIds.size()) {
            throw new BusinessRuleException("unknown-role", "One or more roles do not exist in this pharmacy");
        }
        return found;
    }

    private static String codes(Set<RoleEntity> roles) {
        return roles.stream().map(RoleEntity::code).sorted().collect(Collectors.joining(","));
    }

    public record UserView(UUID id, String email, String displayName, boolean active, Set<String> roles) {

        static UserView of(AppUserEntity user) {
            return new UserView(user.id(), user.email(), user.displayName(), user.active(),
                    user.roles().stream().map(RoleEntity::code).collect(Collectors.toSet()));
        }
    }
}
