package com.pharmacore.supervision.web;

import com.pharmacore.supervision.application.TerminalSupervisionService;
import com.pharmacore.supervision.application.TerminalSupervisionService.PharmacySupervision;
import com.pharmacore.supervision.application.TerminalSupervisionService.PharmacySyncHealth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vendor console: registers, hub link and accounts of each pharmacy. Restricted to the platform
 * issuer by the security filter chain ({@code /api/platform/**}).
 */
@RestController
@RequestMapping("/api/platform/tenants")
class PlatformSupervisionController {

    private final TerminalSupervisionService supervision;

    PlatformSupervisionController(TerminalSupervisionService supervision) {
        this.supervision = supervision;
    }

    /** Per pharmacy: registers total, out of sync, off-line, and the hub's last contact. */
    @GetMapping("/sync-health")
    List<PharmacySyncHealth> syncHealth() {
        return supervision.syncHealth();
    }

    @GetMapping("/{tenantId}/supervision")
    PharmacySupervision pharmacy(@PathVariable UUID tenantId) {
        return supervision.pharmacy(tenantId);
    }

    @DeleteMapping("/{tenantId}/terminals/{terminalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget(@PathVariable UUID tenantId, @PathVariable String terminalId) {
        supervision.forget(tenantId, terminalId);
    }

    @PostMapping("/{tenantId}/terminals/forget-stale")
    Map<String, Integer> forgetStale(@PathVariable UUID tenantId) {
        return Map.of("forgotten", supervision.forgetStale(tenantId));
    }
}
