package com.pharmacore.identity.web;

import com.pharmacore.identity.application.LocalAuthService;
import com.pharmacore.identity.application.RoleManagementService;
import com.pharmacore.identity.application.RoleManagementService.RoleCommand;
import com.pharmacore.identity.application.RoleManagementService.RoleView;
import com.pharmacore.identity.application.UserManagementService;
import com.pharmacore.identity.application.UserManagementService.UserView;
import com.pharmacore.identity.domain.Permission;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class IdentityController {

    private final RoleManagementService roles;
    private final UserManagementService users;
    private final LocalAuthService auth;

    IdentityController(RoleManagementService roles, UserManagementService users, LocalAuthService auth) {
        this.roles = roles;
        this.users = users;
        this.auth = auth;
    }

    /** Profile of the signed-in user, used by the front-end to drive menus and permission directives. */
    @GetMapping("/me")
    Me me() {
        Actor.TenantUser user = TenantContext.requireTenantUser();
        return new Me(user.userId(), user.tenant().value(), user.displayName(), user.roles(), user.permissions(),
                user.maxDiscountPercent());
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    Permission[] permissions() {
        return Permission.values();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('ROLE_MANAGE', 'USER_MANAGE')")
    List<RoleView> listRoles() {
        return roles.list();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    RoleView createRole(@Valid @RequestBody RoleRequest request) {
        return roles.create(request.toCommand());
    }

    @PutMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    RoleView updateRole(@PathVariable UUID roleId, @Valid @RequestBody RoleRequest request) {
        return roles.update(roleId, request.toCommand());
    }

    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    void deleteRole(@PathVariable UUID roleId) {
        roles.delete(roleId);
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    List<UserView> listUsers() {
        return users.list();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserManagementService.Registration registerUser(@Valid @RequestBody UserRequest request) {
        return users.register(request.email(), request.displayName(), request.roleIds());
    }

    @PostMapping("/users/{userId}/password-reset")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    Map<String, String> resetPassword(@PathVariable UUID userId) {
        return Map.of("temporaryPassword", auth.resetPassword(userId));
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        auth.changePassword(request.currentPassword(), request.newPassword());
    }

    @PutMapping("/me/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPin(@Valid @RequestBody PinRequest request) {
        auth.setPin(request.password(), request.pin());
    }

    @PutMapping("/users/{userId}/roles")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView changeRoles(@PathVariable UUID userId, @RequestBody @NotEmpty Set<UUID> roleIds) {
        return users.changeRoles(userId, roleIds);
    }

    @PutMapping("/users/{userId}/active")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    UserView setActive(@PathVariable UUID userId, @RequestBody ActiveRequest request) {
        return users.setActive(userId, request.active());
    }

    record Me(UUID userId, UUID tenantId, String displayName, Set<String> roles, Set<String> permissions,
              BigDecimal maxDiscountPercent) {
    }

    record RoleRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String code,
                       @NotBlank @Size(max = 100) String name,
                       @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal maxDiscountPercent,
                       @NotNull Set<Permission> permissions) {

        RoleCommand toCommand() {
            return new RoleCommand(code, name, maxDiscountPercent, permissions);
        }
    }

    record PasswordChangeRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    record PinRequest(@NotBlank String password, @NotBlank String pin) {
    }

    record UserRequest(@NotBlank @Email String email,
                       @NotBlank @Size(max = 150) String displayName,
                       @NotEmpty Set<UUID> roleIds) {
    }

    record ActiveRequest(boolean active) {
    }
}
