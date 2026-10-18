package com.pharmacore.identity.persistence;

import com.pharmacore.identity.domain.Permission;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Pharmacy-specific role: RBAC permission set plus ABAC attributes (discount ceiling). */
@Entity
@Table(name = "app_role")
public class RoleEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "system_role", nullable = false, updatable = false)
    private boolean systemRole;

    @Column(name = "max_discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDiscountPercent;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "role_permission", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code", nullable = false, length = 64)
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = new HashSet<>();

    @Version
    private long version;

    protected RoleEntity() {
    }

    public RoleEntity(String code, String name, boolean systemRole, BigDecimal maxDiscountPercent,
                      Set<Permission> permissions) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.systemRole = systemRole;
        this.maxDiscountPercent = maxDiscountPercent;
        this.permissions = new HashSet<>(permissions);
    }

    public void redefine(String name, BigDecimal maxDiscountPercent, Set<Permission> permissions) {
        this.name = name;
        this.maxDiscountPercent = maxDiscountPercent;
        this.permissions.clear();
        this.permissions.addAll(permissions);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public boolean systemRole() {
        return systemRole;
    }

    public BigDecimal maxDiscountPercent() {
        return maxDiscountPercent;
    }

    public Set<Permission> permissions() {
        return permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
    }

    public long version() {
        return version;
    }
}
