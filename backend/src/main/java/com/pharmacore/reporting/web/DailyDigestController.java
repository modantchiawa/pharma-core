package com.pharmacore.reporting.web;

import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.reporting.application.DailyDigestService;
import com.pharmacore.reporting.application.DailyDigestService.DigestView;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The owner's evening report, on demand and shareable. */
@RestController
class DailyDigestController {

    private final DailyDigestService digests;
    private final TenantCalendar calendar;

    DailyDigestController(DailyDigestService digests, TenantCalendar calendar) {
        this.digests = digests;
        this.calendar = calendar;
    }

    @GetMapping("/api/v1/reports/daily-digest")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'REPORT_VIEW')")
    DigestView digest(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return digests.view(date == null ? calendar.today() : date);
    }

    /** Publishes today's report now (alert, webhook, SMS), whatever the hour; once per day. */
    @PostMapping("/api/v1/reports/daily-digest/publish")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'REPORT_VIEW')")
    Map<String, Object> publish() {
        LocalDate today = calendar.today();
        var alertId = digests.publish(today);
        return Map.of("date", today.toString(), "published", alertId.isPresent());
    }
}
