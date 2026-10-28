package com.pharmacore.safety.web;

import com.pharmacore.safety.application.SafetyCheckService;
import com.pharmacore.safety.application.SafetyCheckService.CheckResult;
import com.pharmacore.safety.application.SafetyRuleStore;
import com.pharmacore.safety.application.SafetyRuleStore.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Counter vigilance for the terminals: live check of a basket, and the referential feed for the offline copy. */
@RestController
@RequestMapping("/api/v1/safety")
class SafetyController {

    private final SafetyCheckService checks;
    private final SafetyRuleStore rules;

    SafetyController(SafetyCheckService checks, SafetyRuleStore rules) {
        this.checks = checks;
        this.rules = rules;
    }

    record CheckRequest(UUID customerId, @NotEmpty @Size(max = 200) List<UUID> productIds) {
    }

    @PostMapping("/check")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'PRESCRIPTION_DISPENSE')")
    CheckResult check(@Valid @RequestBody CheckRequest request) {
        return checks.check(request.customerId(), request.productIds());
    }

    /** Incremental feed, same cursor shape as the POS catalog: (updated_at, id). */
    @GetMapping("/rules")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'PRESCRIPTION_DISPENSE', 'STOCK_VIEW')")
    Page rules(@RequestParam(defaultValue = "1970-01-01T00:00:00Z")
               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
               @RequestParam(required = false) UUID after,
               @RequestParam(defaultValue = "500") int limit) {
        return rules.changes(since, after, limit);
    }
}
