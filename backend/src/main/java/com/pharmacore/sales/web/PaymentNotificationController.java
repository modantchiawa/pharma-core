package com.pharmacore.sales.web;

import com.pharmacore.sales.application.PaymentChannelSettingsService;
import com.pharmacore.sales.application.PaymentChannelSettingsService.IssuedToken;
import com.pharmacore.sales.application.PaymentChannelSettingsService.Values;
import com.pharmacore.sales.application.PaymentNotificationService;
import com.pharmacore.sales.application.PaymentNotificationService.Feed;
import com.pharmacore.sales.application.PaymentNotificationService.Incoming;
import com.pharmacore.sales.application.PaymentNotificationService.NotificationView;
import com.pharmacore.sales.application.PaymentNotificationService.Source;
import com.pharmacore.sales.domain.PaymentSmsParser.CustomPattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payment confirmations seen by the team, the ones they type, and the channel settings. */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentNotificationController {

    private final PaymentNotificationService notifications;
    private final PaymentChannelSettingsService settings;

    PaymentNotificationController(PaymentNotificationService notifications, PaymentChannelSettingsService settings) {
        this.notifications = notifications;
        this.settings = settings;
    }

    @GetMapping("/notifications")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'PAYMENT_RECONCILE', 'CASH_SESSION_MANAGE')")
    Feed notifications(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        return notifications.feed(status, limit);
    }

    /**
     * A confirmation typed on a terminal (the SMS read on the pharmacy's phone). {@code clientId}
     * makes a replay from an offline terminal idempotent.
     */
    record ManualRequest(@NotNull UUID clientId,
                         @Size(max = 30) String provider,
                         @Positive long amountCents,
                         @Size(max = 64) String reference,
                         @Size(max = 30) String payerPhone,
                         @Size(max = 1000) String rawText,
                         Instant receivedAt,
                         @Size(max = 40) String terminalId) {
    }

    @PostMapping("/notifications")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'PAYMENT_RECONCILE', 'CASH_SESSION_MANAGE')")
    ResponseEntity<NotificationView> record(@Valid @RequestBody ManualRequest request) {
        var incoming = new Incoming(Source.MANUAL, null, request.rawText(), request.provider(), request.amountCents(),
                request.reference(), request.payerPhone(), request.receivedAt(), "MANUAL:" + request.clientId(),
                request.terminalId());
        return notifications.ingest(incoming)
                .map(view -> ResponseEntity.status(HttpStatus.CREATED).body(view))
                .orElseGet(() -> ResponseEntity.ok(notifications.byDedupeKey("MANUAL:" + request.clientId())));
    }

    record ClaimRequest(@NotNull UUID paymentId) {
    }

    @PostMapping("/notifications/{notificationId}/claim")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'PAYMENT_RECONCILE', 'CASH_SESSION_MANAGE')")
    NotificationView claim(@PathVariable UUID notificationId, @Valid @RequestBody ClaimRequest request) {
        return notifications.claim(notificationId, request.paymentId());
    }

    @PostMapping("/notifications/{notificationId}/ignore")
    @PreAuthorize("hasAnyAuthority('PAYMENT_RECONCILE', 'CASH_SESSION_MANAGE')")
    NotificationView ignore(@PathVariable UUID notificationId) {
        return notifications.ignore(notificationId);
    }

    // ------------------------------------------------------------------ channel settings

    @GetMapping("/channel")
    @PreAuthorize("hasAnyAuthority('PAYMENT_RECONCILE', 'CASH_SESSION_MANAGE')")
    PaymentChannelSettingsService.View channel() {
        return settings.view();
    }

    record PatternRequest(@Size(max = 30) String provider, @NotNull @Size(min = 3, max = 500) String regex) {
    }

    record ChannelRequest(boolean autoConfirm, @Min(1) @Max(720) int matchWindowHours,
                          @Size(max = 20) List<@Valid PatternRequest> patterns) {
    }

    @PutMapping("/channel")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    PaymentChannelSettingsService.View updateChannel(@Valid @RequestBody ChannelRequest request) {
        List<CustomPattern> patterns = request.patterns() == null ? List.of()
                : request.patterns().stream().map(p -> new CustomPattern(p.provider(), p.regex())).toList();
        return settings.update(new Values(request.autoConfirm(), request.matchWindowHours(), patterns));
    }

    /** Issues the ingest token, shown once; the previous one stops working immediately. */
    @PostMapping("/channel/token")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    IssuedToken issueToken() {
        return settings.issueToken();
    }
}
