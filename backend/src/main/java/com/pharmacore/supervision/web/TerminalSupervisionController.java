package com.pharmacore.supervision.web;

import com.pharmacore.supervision.application.TerminalSupervisionService;
import com.pharmacore.supervision.application.TerminalSupervisionService.TerminalBoard;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pharmacy's own registers, on its "Serveur local" page (same permissions as the hub
 * status). On the cloud, writes for a pharmacy in hub mode are refused by
 * {@code TopologyGuardFilter}: they belong to the hub.
 */
@RestController
@RequestMapping("/api/v1/pos/terminals")
class TerminalSupervisionController {

    private final TerminalSupervisionService supervision;

    TerminalSupervisionController(TerminalSupervisionService supervision) {
        this.supervision = supervision;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('HUB_ADMIN', 'AUDIT_VIEW')")
    TerminalBoard board() {
        return supervision.localBoard();
    }

    @DeleteMapping("/{terminalId}")
    @PreAuthorize("hasAuthority('HUB_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget(@PathVariable String terminalId) {
        supervision.forgetLocal(terminalId);
    }

    @PostMapping("/forget-stale")
    @PreAuthorize("hasAuthority('HUB_ADMIN')")
    Map<String, Integer> forgetStale() {
        return Map.of("forgotten", supervision.forgetLocalStale());
    }
}
