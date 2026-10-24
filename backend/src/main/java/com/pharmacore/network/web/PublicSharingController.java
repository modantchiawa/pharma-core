package com.pharmacore.network.web;

import com.pharmacore.network.application.PublicSharingService;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity.PublicSharing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The pharmacy's own choices for the public directory. */
@RestController
@RequestMapping("/api/v1/config/public-sharing")
class PublicSharingController {

    private final PublicSharingService sharing;

    PublicSharingController(PublicSharingService sharing) {
        this.sharing = sharing;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('DIRECTORY_SHARING_MANAGE', 'CONFIG_BRANDING_MANAGE')")
    PublicSharing current() {
        return sharing.current();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('DIRECTORY_SHARING_MANAGE')")
    PublicSharing update(@Valid @RequestBody PublicSharingRequest request) {
        return sharing.update(new PublicSharing(request.publicListing(), request.publicAvailability(),
                request.latitude(), request.longitude(), blank(request.openingHours()), blank(request.whatsapp())));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record PublicSharingRequest(boolean publicListing,
                                boolean publicAvailability,
                                @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
                                @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
                                @Size(max = 300) String openingHours,
                                @Size(max = 30) @Pattern(regexp = "^$|^\\+?[0-9][0-9 ]{5,29}$") String whatsapp) {
    }
}
