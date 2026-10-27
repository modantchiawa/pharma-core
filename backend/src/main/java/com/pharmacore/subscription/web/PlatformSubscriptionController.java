package com.pharmacore.subscription.web;

import com.pharmacore.subscription.application.LicenseKeyRing;
import com.pharmacore.subscription.application.LicenseKeyRing.PublicLicenseKey;
import com.pharmacore.subscription.application.PlatformSubscriptionService;
import com.pharmacore.subscription.application.PlatformSubscriptionService.PaymentCommand;
import com.pharmacore.subscription.application.SubscriptionViews.PlanView;
import com.pharmacore.subscription.application.SubscriptionViews.TenantSubscription;
import com.pharmacore.subscription.application.SubscriptionViews.TenantSubscriptionDetail;
import com.pharmacore.subscription.domain.SubscriptionPaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Vendor console (platform issuer only, enforced by the security filter chain on /api/platform). */
@RestController
@RequestMapping("/api/platform/subscriptions")
class PlatformSubscriptionController {

    private final PlatformSubscriptionService subscriptions;
    private final LicenseKeyRing keys;

    PlatformSubscriptionController(PlatformSubscriptionService subscriptions, LicenseKeyRing keys) {
        this.subscriptions = subscriptions;
        this.keys = keys;
    }

    @GetMapping
    List<TenantSubscription> overview() {
        return subscriptions.overview();
    }

    @GetMapping("/plans")
    List<PlanView> plans() {
        return subscriptions.plans();
    }

    @GetMapping("/{tenantId}")
    TenantSubscriptionDetail detail(@PathVariable UUID tenantId) {
        return subscriptions.detail(tenantId);
    }

    @PostMapping("/{tenantId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    TenantSubscriptionDetail recordPayment(@PathVariable UUID tenantId, @Valid @RequestBody PaymentRequest request) {
        return subscriptions.recordPayment(tenantId, new PaymentCommand(request.amountXof(), request.method(),
                request.reference(), request.months(), request.paidAt(), request.note()));
    }

    @PutMapping("/{tenantId}/plan")
    TenantSubscriptionDetail changePlan(@PathVariable UUID tenantId, @Valid @RequestBody PlanRequest request) {
        return subscriptions.changePlan(tenantId, request.plan());
    }

    @PostMapping("/{tenantId}/suspension")
    TenantSubscriptionDetail suspend(@PathVariable UUID tenantId, @Valid @RequestBody SuspensionRequest request) {
        return subscriptions.suspend(tenantId, request.reason());
    }

    @PostMapping("/{tenantId}/reactivation")
    TenantSubscriptionDetail reactivate(@PathVariable UUID tenantId) {
        return subscriptions.reactivate(tenantId);
    }

    /** Replaces the license signing key; hubs learn the new one through its endorsement. */
    @PostMapping("/license-keys/rotation")
    PublicLicenseKey rotateKey() {
        return keys.rotate();
    }

    record PaymentRequest(@Positive long amountXof,
                          @NotNull SubscriptionPaymentMethod method,
                          @Size(max = 80) String reference,
                          @Min(1) @Max(36) int months,
                          LocalDate paidAt,
                          @Size(max = 255) String note) {
    }

    record PlanRequest(@NotBlank @Size(max = 30) String plan) {
    }

    record SuspensionRequest(@NotBlank @Size(max = 255) String reason) {
    }
}
