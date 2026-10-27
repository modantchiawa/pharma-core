package com.pharmacore.control.web;

import com.pharmacore.control.application.AlertService;
import com.pharmacore.control.application.AlertService.AlertView;
import com.pharmacore.control.application.AlertService.DeliveryView;
import com.pharmacore.control.application.AlertService.Feed;
import com.pharmacore.control.application.ControlDashboardService;
import com.pharmacore.control.application.ControlDashboardService.ApprovalView;
import com.pharmacore.control.application.ControlDashboardService.Event;
import com.pharmacore.control.application.ControlDashboardService.Overview;
import com.pharmacore.control.application.ControlDashboardService.ReceiptCheckView;
import com.pharmacore.control.application.ControlSettingsService;
import com.pharmacore.control.application.ControlSettingsService.ActivityDay;
import com.pharmacore.control.application.ControlSettingsService.SettingsUpdate;
import com.pharmacore.control.application.ControlSettingsService.SettingsView;
import com.pharmacore.control.application.DailyCountService;
import com.pharmacore.control.application.DailyCountService.DailyCountView;
import com.pharmacore.control.application.DailyCountService.DailySummary;
import com.pharmacore.control.application.DailyCountService.Variance;
import com.pharmacore.control.application.NarcoticsRegisterService;
import com.pharmacore.control.application.NarcoticsRegisterService.Register;
import com.pharmacore.control.domain.AnomalyRules.Metric;
import com.pharmacore.control.persistence.ControlSettingsEntity.Separation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal control: daily micro-count, dashboard, narcotics register, alerts and settings. */
@RestController
@RequestMapping("/api/v1/control")
class ControlController {

    private final ControlSettingsService settings;
    private final DailyCountService dailyCounts;
    private final ControlDashboardService dashboard;
    private final NarcoticsRegisterService narcotics;
    private final AlertService alerts;

    ControlController(ControlSettingsService settings, DailyCountService dailyCounts,
                      ControlDashboardService dashboard, NarcoticsRegisterService narcotics, AlertService alerts) {
        this.settings = settings;
        this.dailyCounts = dailyCounts;
        this.dashboard = dashboard;
        this.narcotics = narcotics;
        this.alerts = alerts;
    }

    // ------------------------------------------------------------------ four eyes

    /** Thresholds the screens use to ask for the second signature before sending the request. */
    record FourEyesThresholds(long adjustmentApprovalCents, long refundApprovalCents, long cancellationApprovalCents) {
    }

    @GetMapping("/four-eyes")
    @PreAuthorize("isAuthenticated()")
    FourEyesThresholds fourEyes() {
        var values = settings.effective().values();
        return new FourEyesThresholds(values.adjustmentApprovalCents(), values.refundApprovalCents(),
                values.cancellationApprovalCents());
    }

    // ------------------------------------------------------------------ daily micro-count

    /** Today's draw (made by the first call of the day, the same afterwards). */
    @PostMapping("/daily-count")
    @PreAuthorize("hasAnyAuthority('STOCK_COUNT', 'STOCK_COUNT_VALIDATE', 'CONTROL_VIEW')")
    DailyCountView today() {
        return dailyCounts.today();
    }

    @PostMapping("/daily-count/start")
    @PreAuthorize("hasAnyAuthority('STOCK_COUNT', 'STOCK_COUNT_VALIDATE')")
    DailyCountView start() {
        return dailyCounts.start();
    }

    @GetMapping("/daily-counts")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    List<DailySummary> dailyHistory(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dailyCounts.history(from, to);
    }

    @GetMapping("/daily-counts/{stockCountId}/variances")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    List<Variance> variances(@PathVariable UUID stockCountId) {
        return dailyCounts.variances(stockCountId);
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    Overview overview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboard.overview(from, to);
    }

    @GetMapping("/employees/{userId}/events")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    List<Event> events(@PathVariable UUID userId, @RequestParam Metric metric,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboard.events(userId, metric, from, to);
    }

    @GetMapping("/employees/{userId}/audit")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    List<Event> audit(@PathVariable UUID userId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboard.auditTrail(userId, from, to);
    }

    @GetMapping("/narcotics")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    Register narcotics(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return narcotics.register(from, to);
    }

    @GetMapping("/receipt-checks")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    List<ReceiptCheckView> receiptChecks(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboard.receiptChecks(from, to);
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    List<ApprovalView> approvals(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboard.approvals(from, to);
    }

    // ------------------------------------------------------------------ alerts

    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    Feed alerts(@RequestParam(defaultValue = "false") boolean unreadOnly,
                @RequestParam(defaultValue = "50") int limit) {
        return alerts.feed(unreadOnly, limit);
    }

    @GetMapping("/alerts/unread-count")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    Map<String, Long> unread() {
        return Map.of("unread", alerts.unread());
    }

    @PostMapping("/alerts/{alertId}/acknowledgement")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    AlertView acknowledge(@PathVariable UUID alertId) {
        return alerts.acknowledge(alertId);
    }

    @PostMapping("/alerts/acknowledgement")
    @PreAuthorize("hasAuthority('CONTROL_VIEW')")
    Map<String, Integer> acknowledgeAll() {
        return Map.of("acknowledged", alerts.acknowledgeAll());
    }

    @PostMapping("/alerts/test")
    @PreAuthorize("hasAuthority('CONTROL_SETTINGS')")
    AlertView test() {
        return alerts.test();
    }

    @GetMapping("/webhook/deliveries")
    @PreAuthorize("hasAuthority('CONTROL_SETTINGS')")
    List<DeliveryView> deliveries(@RequestParam(defaultValue = "20") int limit) {
        return alerts.deliveries(limit);
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('CONTROL_SETTINGS')")
    SettingsView settings() {
        return settings.view();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('CONTROL_SETTINGS')")
    SettingsView update(@Valid @RequestBody SettingsRequest request) {
        return settings.update(new SettingsUpdate(request.microCountSize(), request.adjustmentApprovalCents(),
                request.refundApprovalCents(), request.cancellationApprovalCents(), request.alertAdjustmentCents(),
                request.alertCancellationCents(), request.alertCashVarianceCents(), request.pinFailureAlertCount(),
                request.blindReceiving(), request.receivingSeparation(), request.activityHours(),
                request.webhookEnabled(), request.webhookUrl(), request.webhookSecret(), request.version()));
    }

    /** {@code webhookSecret}: null keeps the stored secret. */
    record SettingsRequest(@Min(5) @Max(10) int microCountSize,
                           @PositiveOrZero long adjustmentApprovalCents,
                           @PositiveOrZero long refundApprovalCents,
                           @PositiveOrZero long cancellationApprovalCents,
                           @PositiveOrZero long alertAdjustmentCents,
                           @PositiveOrZero long alertCancellationCents,
                           @PositiveOrZero long alertCashVarianceCents,
                           @Min(2) @Max(10) int pinFailureAlertCount,
                           boolean blindReceiving,
                           @NotNull Separation receivingSeparation,
                           @NotEmpty @Size(max = 7) List<@Valid ActivityDay> activityHours,
                           boolean webhookEnabled,
                           @Size(max = 500) String webhookUrl,
                           @Size(max = 128) String webhookSecret,
                           @PositiveOrZero long version) {
    }
}
