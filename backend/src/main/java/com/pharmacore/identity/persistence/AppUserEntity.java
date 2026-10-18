package com.pharmacore.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.TenantId;

/** Pharmacy staff member, linked to the identity provider through {@code externalSubject} (JWT {@code sub}). */
@Entity
@Table(name = "app_user")
public class AppUserEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "external_subject", nullable = false, updatable = false)
    private String externalSubject;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToMany
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleEntity> roles = new HashSet<>();

    @Version
    private long version;

    protected AppUserEntity() {
    }

    public AppUserEntity(String externalSubject, String email, String displayName, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.externalSubject = externalSubject;
        this.email = email;
        this.displayName = displayName;
        this.active = true;
        this.createdAt = createdAt;
    }

    public void assignRoles(Set<RoleEntity> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String externalSubject() {
        return externalSubject;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public boolean active() {
        return active;
    }

    public Set<RoleEntity> roles() {
        return Set.copyOf(roles);
    }

    /** Union of the permissions granted by all roles. */
    public Set<String> permissionCodes() {
        return roles.stream()
                .flatMap(role -> role.permissions().stream())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Most permissive discount ceiling among the user's roles. */
    public BigDecimal maxDiscountPercent() {
        return roles.stream().map(RoleEntity::maxDiscountPercent).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    public Set<String> roleCodes() {
        return roles.stream().map(RoleEntity::code).collect(Collectors.toUnmodifiableSet());
    }
}
