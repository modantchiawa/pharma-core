package com.pharmacore.subscription.web;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.subscription.application.LicenseIssuer;
import com.pharmacore.subscription.application.LicenseIssuer.IssuedLicense;
import com.pharmacore.subscription.application.LicenseKeyRing;
import com.pharmacore.subscription.application.LicenseKeyRing.PublicLicenseKey;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cloud endpoints a paired hub calls on each synchronization cycle (authority ROLE_HUB_NODE). */
@RestController
class LicenseSyncController {

    private final LicenseIssuer issuer;
    private final LicenseKeyRing keys;

    LicenseSyncController(LicenseIssuer issuer, LicenseKeyRing keys) {
        this.issuer = issuer;
        this.keys = keys;
    }

    /** The hub's license, re-issued when old enough so that its offline window slides. */
    @PostMapping("/api/sync/license")
    IssuedLicense license() {
        return issuer.current(hub().tenant().value());
    }

    /** Public keys of the license authority, each endorsed by its predecessor. */
    @GetMapping("/api/sync/license-keys")
    List<PublicLicenseKey> licenseKeys() {
        hub();
        return keys.publicKeys();
    }

    private static Actor.HubNode hub() {
        if (TenantContext.actor() instanceof Actor.HubNode hub) {
            return hub;
        }
        throw new BusinessRuleException("hub-only", "Réservé aux serveurs locaux appairés");
    }
}
