package com.pharmacore.tenant.web;

import com.pharmacore.tenant.application.PlatformSupervisionService;
import com.pharmacore.tenant.application.PlatformSupervisionService.TenantHealth;
import com.pharmacore.tenant.application.PlatformSupervisionService.TerminalHealth;
import com.pharmacore.tenant.application.TenantProvisioningService;
import com.pharmacore.tenant.application.TenantProvisioningService.NewPharmacy;
import com.pharmacore.tenant.application.TenantProvisioningService.Owner;
import com.pharmacore.tenant.application.TenantProvisioningService.TenantSummary;
import com.pharmacore.tenant.persistence.TenantStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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

/** Super-Admin endpoints; access restricted to the platform issuer by the security filter chain. */
@RestController
@RequestMapping("/api/platform/tenants")
class PlatformTenantController {

    private final TenantProvisioningService provisioning;
    private final PlatformSupervisionService supervision;

    PlatformTenantController(TenantProvisioningService provisioning, PlatformSupervisionService supervision) {
        this.provisioning = provisioning;
        this.supervision = supervision;
    }

    @GetMapping
    List<TenantHealth> overview() {
        return supervision.overview();
    }

    @GetMapping("/{tenantId}/terminals")
    List<TerminalHealth> terminals(@PathVariable UUID tenantId) {
        return supervision.terminals(tenantId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TenantSummary onboard(@Valid @RequestBody OnboardingRequest request) {
        return provisioning.register(new NewPharmacy(request.slug(), request.legalName(), request.displayName(),
                request.countryCode(), request.currency(), request.timeZone(), request.subscriptionPlan(),
                request.subscriptionExpiresAt(),
                new Owner(request.ownerEmail(), request.ownerDisplayName())));
    }

    @PutMapping("/{tenantId}/status")
    TenantSummary changeStatus(@PathVariable UUID tenantId, @Valid @RequestBody StatusRequest request) {
        return provisioning.changeStatus(tenantId, request.status(), request.reason());
    }

    @PutMapping("/{tenantId}/subscription")
    TenantSummary renew(@PathVariable UUID tenantId, @Valid @RequestBody SubscriptionRequest request) {
        return provisioning.renewSubscription(tenantId, request.plan(), request.expiresAt());
    }

    record OnboardingRequest(@NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$") String slug,
                             @NotBlank @Size(max = 200) String legalName,
                             @NotBlank @Size(max = 150) String displayName,
                             @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
                             @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                             @NotBlank String timeZone,
                             @NotBlank @Size(max = 30) String subscriptionPlan,
                             @Future Instant subscriptionExpiresAt,
                             @NotBlank @Email String ownerEmail,
                             @NotBlank String ownerDisplayName) {
    }

    record StatusRequest(@NotNull TenantStatus status, @NotBlank @Size(max = 255) String reason) {
    }

    record SubscriptionRequest(@NotBlank @Size(max = 30) String plan, @NotNull @Future Instant expiresAt) {
    }
}
