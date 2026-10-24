package com.pharmacore.network.application;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity.PublicSharing;
import com.pharmacore.tenant.persistence.PharmacyConfigRepository;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the pharmacy exposes in the public directory. Written on the hub, replicated to the cloud. */
@Service
public class PublicSharingService {

    private final PharmacyConfigRepository configs;
    private final AuditTrail audit;
    private final Clock clock;

    public PublicSharingService(PharmacyConfigRepository configs, AuditTrail audit, Clock clock) {
        this.configs = configs;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicSharing current() {
        return load().publicSharing();
    }

    @Transactional
    public PublicSharing update(PublicSharing sharing) {
        var config = load();
        config.updatePublicSharing(sharing, clock.instant());
        audit.record("PUBLIC_SHARING_UPDATED", "PHARMACY_CONFIG", config.tenantId(), Map.of(
                "publicListing", Boolean.toString(sharing.publicListing()),
                "publicAvailability", Boolean.toString(sharing.publicListing() && sharing.publicAvailability()),
                "geolocated", Boolean.toString(sharing.latitude() != null && sharing.longitude() != null)));
        return config.publicSharing();
    }

    private PharmacyConfigEntity load() {
        return configs.findCurrent().orElseThrow(() -> new NotFoundException("Pharmacy configuration", "current"));
    }
}
