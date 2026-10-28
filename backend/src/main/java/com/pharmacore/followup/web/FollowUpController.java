package com.pharmacore.followup.web;

import com.pharmacore.followup.application.CustomerMessageService;
import com.pharmacore.followup.application.CustomerMessageService.MessageView;
import com.pharmacore.followup.application.CustomerMessageService.Queue;
import com.pharmacore.followup.application.FollowUpGenerator;
import com.pharmacore.followup.application.FollowUpSettingsService;
import com.pharmacore.followup.application.FollowUpSettingsService.Values;
import com.pharmacore.followup.application.FollowUpSettingsService.View;
import com.pharmacore.followup.application.ProductWishService;
import com.pharmacore.followup.application.ProductWishService.WishView;
import com.pharmacore.followup.domain.MessageKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Patient follow-up: the message queue, product waits, settings. */
@RestController
@RequestMapping("/api/v1/followup")
class FollowUpController {

    private final CustomerMessageService messages;
    private final ProductWishService wishes;
    private final FollowUpSettingsService settings;
    private final FollowUpGenerator generator;

    FollowUpController(CustomerMessageService messages, ProductWishService wishes, FollowUpSettingsService settings,
                       FollowUpGenerator generator) {
        this.messages = messages;
        this.wishes = wishes;
        this.settings = settings;
        this.generator = generator;
    }

    // ------------------------------------------------------------------ messages

    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('FOLLOWUP_VIEW')")
    Queue messages(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        return messages.queue(status, limit);
    }

    record ComposeRequest(@NotNull UUID customerId, @NotBlank @Size(max = 600) String text) {
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('FOLLOWUP_VIEW')")
    MessageView compose(@Valid @RequestBody ComposeRequest request) {
        return messages.compose(request.customerId(), request.text());
    }

    @PostMapping("/messages/{messageId}/sent")
    @PreAuthorize("hasAuthority('FOLLOWUP_VIEW')")
    MessageView markSent(@PathVariable UUID messageId) {
        return messages.markSent(messageId);
    }

    @PostMapping("/messages/{messageId}/cancel")
    @PreAuthorize("hasAuthority('FOLLOWUP_VIEW')")
    MessageView cancel(@PathVariable UUID messageId) {
        return messages.cancel(messageId);
    }

    /** Runs the daily rules now (they are idempotent), for a team that wants the list before opening. */
    @PostMapping("/messages/generate")
    @PreAuthorize("hasAuthority('FOLLOWUP_VIEW')")
    FollowUpGenerator.Report generate() {
        return generator.generate();
    }

    // ------------------------------------------------------------------ product waits

    @GetMapping("/wishes")
    @PreAuthorize("hasAnyAuthority('FOLLOWUP_VIEW', 'SALE_CREATE')")
    List<WishView> wishes(@RequestParam(required = false) String status, @RequestParam(defaultValue = "100") int limit) {
        return wishes.list(status, limit);
    }

    record WishRequest(@NotNull UUID customerId, @NotNull UUID productId, @Min(1) @Max(999) int quantity) {
    }

    @PostMapping("/wishes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('FOLLOWUP_VIEW', 'SALE_CREATE')")
    WishView wish(@Valid @RequestBody WishRequest request) {
        return wishes.create(request.customerId(), request.productId(), request.quantity());
    }

    @PostMapping("/wishes/{wishId}/cancel")
    @PreAuthorize("hasAnyAuthority('FOLLOWUP_VIEW', 'SALE_CREATE')")
    WishView cancelWish(@PathVariable UUID wishId) {
        return wishes.cancel(wishId);
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @PreAuthorize("hasAnyAuthority('FOLLOWUP_SETTINGS', 'FOLLOWUP_VIEW')")
    View settings() {
        return settings.view();
    }

    record SettingsRequest(boolean enabled, boolean treatmentEndEnabled, boolean renewalEnabled,
                           @Min(0) @Max(30) int renewalDaysAhead, boolean productWishEnabled,
                           boolean creditReminderEnabled, @Min(1) @Max(365) int creditReminderAfterDays,
                           @Min(1) @Max(365) int creditReminderEveryDays,
                           Map<MessageKind, @Size(max = 600) String> templates,
                           @Size(max = 500) String smsGatewayUrl,
                           /** null keeps the stored token, "" clears it */
                           @Size(max = 200) String smsGatewayToken,
                           @Size(max = 11) String smsSender,
                           boolean digestEnabled, @Min(0) @Max(23) int digestHour,
                           @Size(max = 30) String digestPhone) {
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('FOLLOWUP_SETTINGS')")
    View update(@Valid @RequestBody SettingsRequest request) {
        return settings.update(new FollowUpSettingsService.Update(new Values(request.enabled(),
                request.treatmentEndEnabled(), request.renewalEnabled(), request.renewalDaysAhead(),
                request.productWishEnabled(), request.creditReminderEnabled(), request.creditReminderAfterDays(),
                request.creditReminderEveryDays(), request.templates() == null ? Map.of() : request.templates(),
                request.smsGatewayUrl(), request.smsSender(), request.digestEnabled(), request.digestHour(),
                request.digestPhone()), request.smsGatewayToken()));
    }
}
