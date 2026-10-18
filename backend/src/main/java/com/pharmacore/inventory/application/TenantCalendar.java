package com.pharmacore.inventory.application;

import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/** Calendar date in the pharmacy's own time zone (expiry dates are local dates). */
@Component
public class TenantCalendar {

    private final TenantRepository tenants;
    private final Clock clock;

    public TenantCalendar(TenantRepository tenants, Clock clock) {
        this.tenants = tenants;
        this.clock = clock;
    }

    public LocalDate today() {
        var zone = tenants.findById(TenantContext.requireTenant().value())
                .map(tenant -> ZoneId.of(tenant.timeZone()))
                .orElse(ZoneId.of("Europe/Paris"));
        return LocalDate.now(clock.withZone(zone));
    }
}
