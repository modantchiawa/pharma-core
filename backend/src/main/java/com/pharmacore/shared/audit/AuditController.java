package com.pharmacore.shared.audit;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAuthority('AUDIT_VIEW')")
class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/events")
    List<AuditRecord> events(@RequestParam(defaultValue = "100") int limit,
                             @RequestParam(required = false) Long beforeSequence) {
        return audit.latest(limit, beforeSequence);
    }

    @GetMapping("/verification")
    AuditQueryService.ChainVerification verify() {
        return audit.verifyChain();
    }
}
