package com.pharmacore.control.application;

import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether an instant falls outside the pharmacy's activity hours. A night (or Sunday) on guard
 * duty in the network's guard schedule counts as open: selling then is the point.
 */
@Component
public class OperatingHours {

    private final ControlSettingsService settings;
    private final TenantRepository tenants;
    private final JdbcClient jdbc;

    public OperatingHours(ControlSettingsService settings, TenantRepository tenants, JdbcClient jdbc) {
        this.settings = settings;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean outside(Instant at) {
        return !settings.effective().hours().isOpen(local(at)) && !onGuard(at);
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public LocalDateTime local(Instant at) {
        return LocalDateTime.ofInstant(at, zone());
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public ZoneId zone() {
        return tenants.findById(TenantContext.requireTenant().value())
                .map(tenant -> ZoneId.of(tenant.timeZone()))
                .orElse(ZoneId.of("UTC"));
    }

    private boolean onGuard(Instant at) {
        UUID tenant = TenantContext.requireTenant().value();
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM guard_schedule
                                       WHERE pharmacy_tenant_id = :tenant AND starts_at <= :at AND ends_at > :at)""")
                .param("tenant", tenant)
                .param("at", Timestamp.from(at))
                .query(Boolean.class)
                .single();
    }
}
