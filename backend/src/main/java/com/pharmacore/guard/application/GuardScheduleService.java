package com.pharmacore.guard.application;

import com.pharmacore.guard.domain.GuardShareText;
import com.pharmacore.guard.persistence.GuardScheduleEntity;
import com.pharmacore.guard.persistence.GuardScheduleRepository;
import com.pharmacore.guard.persistence.GuardType;
import com.pharmacore.network.application.NetworkDirectoryService;
import com.pharmacore.network.application.NetworkDirectoryService.DirectoryEntry;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuardScheduleService {

    static final Duration MAX_RANGE = Duration.ofDays(400);

    private final GuardScheduleRepository guards;
    private final NetworkDirectoryService directory;
    private final TenantRepository tenants;
    private final AuditTrail audit;
    private final Clock clock;

    public GuardScheduleService(GuardScheduleRepository guards, NetworkDirectoryService directory,
                                TenantRepository tenants, AuditTrail audit, Clock clock) {
        this.guards = guards;
        this.directory = directory;
        this.tenants = tenants;
        this.audit = audit;
        this.clock = clock;
    }

    /** The day's roster as a message to forward (WhatsApp, SMS), in the pharmacy's time zone. */
    @Transactional
    public ShareText shareText(UUID networkId, LocalDate date) {
        ZoneId zone = tenants.findById(TenantContext.requireTenant().value())
                .map(tenant -> ZoneId.of(tenant.timeZone()))
                .orElse(ZoneId.of("UTC"));
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<GuardSlot> slots = roster(networkId, from, to);
        String scope = networkId == null ? null : directory.directory().stream()
                .filter(e -> e.networkId().equals(networkId))
                .map(e -> e.region() == null || e.region().isBlank() ? e.networkName() : e.region())
                .findFirst()
                .orElse(null);
        String text = GuardShareText.render(scope, date, zone, slots.stream()
                .map(s -> new GuardShareText.Entry(s.pharmacyName(), s.city(), s.phone(), s.type().name(),
                        s.startsAt(), s.endsAt()))
                .toList());
        audit.record("GUARD_ROSTER_SHARED", "GUARD", networkId == null ? "all" : networkId, Map.of(
                "date", date.toString(), "slots", Integer.toString(slots.size())));
        return new ShareText(date, zone.getId(), slots.size(), text);
    }

    public record ShareText(LocalDate date, String timeZone, int slots, String text) {
    }

    @Transactional(readOnly = true)
    public List<GuardSlot> roster(UUID networkId, Instant from, Instant to) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BusinessRuleException("invalid-range", "Range must be positive and at most 400 days");
        }
        Map<String, DirectoryEntry> pharmacies = directoryIndex();
        return guards.findOverlapping(networkId, from, to).stream()
                .map(slot -> GuardSlot.of(slot, pharmacies.get(key(slot.networkId(), slot.pharmacyTenantId()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GuardSlot> onDutyNow(UUID networkId) {
        Instant now = clock.instant();
        return roster(networkId, now, now.plusSeconds(1));
    }

    @Transactional
    public GuardSlot schedule(UUID networkId, UUID pharmacyTenantId, GuardType type, Instant startsAt, Instant endsAt,
                              String notes) {
        requireCoordinator(networkId);
        var slot = guards.save(new GuardScheduleEntity(networkId, pharmacyTenantId, type, startsAt, endsAt, notes,
                TenantContext.requireTenantUser().userId()));
        guards.flush();
        audit.record("GUARD_SCHEDULED", "GUARD", slot.id(), describe(slot));
        return GuardSlot.of(slot, directoryIndex().get(key(networkId, pharmacyTenantId)));
    }

    @Transactional
    public GuardSlot reschedule(UUID slotId, UUID pharmacyTenantId, GuardType type, Instant startsAt, Instant endsAt,
                                String notes) {
        var slot = guards.findById(slotId).orElseThrow(() -> new NotFoundException("Guard slot", slotId));
        requireCoordinator(slot.networkId());
        slot.reschedule(pharmacyTenantId, type, startsAt, endsAt, notes);
        guards.flush();
        audit.record("GUARD_RESCHEDULED", "GUARD", slotId, describe(slot));
        return GuardSlot.of(slot, directoryIndex().get(key(slot.networkId(), pharmacyTenantId)));
    }

    @Transactional
    public void remove(UUID slotId) {
        var slot = guards.findById(slotId).orElseThrow(() -> new NotFoundException("Guard slot", slotId));
        requireCoordinator(slot.networkId());
        guards.delete(slot);
        audit.record("GUARD_REMOVED", "GUARD", slotId, describe(slot));
    }

    /** Clear error message up front; the RLS policy enforces the same rule at the database level. */
    private void requireCoordinator(UUID networkId) {
        var tenant = TenantContext.requireTenant().value();
        boolean coordinator = directory.directory().stream()
                .anyMatch(e -> e.networkId().equals(networkId) && e.tenantId().equals(tenant) && e.coordinator());
        if (!coordinator) {
            throw new BusinessRuleException("not-coordinator", "Only the network coordinator manages the duty roster");
        }
    }

    private Map<String, DirectoryEntry> directoryIndex() {
        return directory.directory().stream()
                .collect(Collectors.toMap(e -> key(e.networkId(), e.tenantId()), e -> e, (a, b) -> a));
    }

    private static String key(UUID networkId, UUID tenantId) {
        return networkId + ":" + tenantId;
    }

    private static Map<String, String> describe(GuardScheduleEntity slot) {
        return Map.of(
                "networkId", slot.networkId().toString(),
                "pharmacy", slot.pharmacyTenantId().toString(),
                "type", slot.guardType().name(),
                "startsAt", slot.startsAt().toString(),
                "endsAt", slot.endsAt().toString());
    }

    public record GuardSlot(UUID id, UUID networkId, UUID pharmacyTenantId, String pharmacyName, String city,
                            String phone, GuardType type, Instant startsAt, Instant endsAt, String notes,
                            long version) {

        static GuardSlot of(GuardScheduleEntity slot, DirectoryEntry pharmacy) {
            return new GuardSlot(slot.id(), slot.networkId(), slot.pharmacyTenantId(),
                    pharmacy == null ? null : pharmacy.pharmacyName(),
                    pharmacy == null ? null : pharmacy.city(),
                    pharmacy == null ? null : pharmacy.phone(),
                    slot.guardType(), slot.startsAt(), slot.endsAt(), slot.notes(), slot.version());
        }
    }
}
