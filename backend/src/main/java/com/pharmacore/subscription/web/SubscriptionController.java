package com.pharmacore.subscription.web;

import com.pharmacore.subscription.application.PharmacySubscriptionService;
import com.pharmacore.subscription.application.SubscriptionViews.LicenseStatus;
import com.pharmacore.subscription.application.SubscriptionViews.PharmacySubscription;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The pharmacy's subscription: state for every terminal, details for the owner. */
@RestController
@RequestMapping("/api/v1/subscription")
class SubscriptionController {

    private final PharmacySubscriptionService subscriptions;

    SubscriptionController(PharmacySubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    /** Polled by every terminal (banner, counter), cached there to enforce the block offline. */
    @GetMapping("/state")
    LicenseStatus state() {
        return subscriptions.status();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SUBSCRIPTION_VIEW')")
    PharmacySubscription mine() {
        return subscriptions.mine();
    }

    /** Asks the cloud for a fresh license now instead of waiting for the next synchronization. */
    @PostMapping("/refresh")
    LicenseStatus refresh() {
        return subscriptions.refresh();
    }
}
