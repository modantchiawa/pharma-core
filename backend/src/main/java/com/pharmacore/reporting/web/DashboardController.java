package com.pharmacore.reporting.web;

import com.pharmacore.reporting.application.DashboardService;
import com.pharmacore.reporting.application.DashboardService.Dashboard;
import com.pharmacore.shared.web.BusinessRuleException;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {

    private static final int MAX_DAYS = 366;

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/dashboard")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'REPORT_VIEW')")
    Dashboard dashboard(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("invalid-period", "La fin de la période précède son début");
        }
        if (to.toEpochDay() - from.toEpochDay() >= MAX_DAYS) {
            throw new BusinessRuleException("period-too-long", "La période ne peut pas dépasser un an");
        }
        return dashboard.build(from, to);
    }
}
