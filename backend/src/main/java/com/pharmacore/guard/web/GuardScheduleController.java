package com.pharmacore.guard.web;

import com.pharmacore.guard.application.GuardScheduleService;
import com.pharmacore.guard.application.GuardScheduleService.GuardSlot;
import com.pharmacore.guard.persistence.GuardType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/guards")
class GuardScheduleController {

    private final GuardScheduleService guards;

    GuardScheduleController(GuardScheduleService guards) {
        this.guards = guards;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('GUARD_VIEW')")
    List<GuardSlot> roster(@RequestParam(required = false) UUID networkId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return guards.roster(networkId, from, to);
    }

    @GetMapping("/now")
    @PreAuthorize("hasAuthority('GUARD_VIEW')")
    List<GuardSlot> onDutyNow(@RequestParam(required = false) UUID networkId) {
        return guards.onDutyNow(networkId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('GUARD_MANAGE')")
    GuardSlot schedule(@Valid @RequestBody SlotRequest request) {
        return guards.schedule(request.networkId(), request.pharmacyTenantId(), request.type(), request.startsAt(),
                request.endsAt(), request.notes());
    }

    @PutMapping("/{slotId}")
    @PreAuthorize("hasAuthority('GUARD_MANAGE')")
    GuardSlot reschedule(@PathVariable UUID slotId, @Valid @RequestBody SlotRequest request) {
        return guards.reschedule(slotId, request.pharmacyTenantId(), request.type(), request.startsAt(),
                request.endsAt(), request.notes());
    }

    @DeleteMapping("/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('GUARD_MANAGE')")
    void remove(@PathVariable UUID slotId) {
        guards.remove(slotId);
    }

    record SlotRequest(@NotNull UUID networkId,
                       @NotNull UUID pharmacyTenantId,
                       @NotNull GuardType type,
                       @NotNull Instant startsAt,
                       @NotNull Instant endsAt,
                       @Size(max = 500) String notes) {
    }
}
