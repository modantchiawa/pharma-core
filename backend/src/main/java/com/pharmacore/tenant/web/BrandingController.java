package com.pharmacore.tenant.web;

import com.pharmacore.tenant.application.BrandingService;
import com.pharmacore.tenant.application.BrandingService.BrandingView;
import com.pharmacore.tenant.application.BrandingService.Logo;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity.Branding;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class BrandingController {

    private static final String HEX = "^#[0-9A-Fa-f]{6}$";

    private final BrandingService branding;

    BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @GetMapping("/api/v1/pharmacy/branding")
    BrandingView current() {
        return branding.current();
    }

    @PutMapping("/api/v1/pharmacy/branding")
    @PreAuthorize("hasAuthority('CONFIG_BRANDING_MANAGE')")
    BrandingView update(@Valid @RequestBody BrandingRequest request) {
        return branding.update(request.toBranding());
    }

    @PutMapping(path = "/api/v1/pharmacy/branding/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CONFIG_BRANDING_MANAGE')")
    BrandingView uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
        return branding.replaceLogo(file.getBytes());
    }

    @GetMapping("/api/v1/pharmacy/branding/logo")
    ResponseEntity<byte[]> logo(@RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        return serve(branding.logo(), ifNoneMatch);
    }

    @GetMapping("/api/public/pharmacies/{slug}/branding")
    ResponseEntity<BrandingView> publicBranding(@PathVariable String slug) {
        return branding.publicBranding(slug)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))).body(view))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/public/pharmacies/{slug}/logo")
    ResponseEntity<byte[]> publicLogo(@PathVariable String slug,
                                      @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        return serve(branding.publicLogo(slug), ifNoneMatch);
    }

    private static ResponseEntity<byte[]> serve(Optional<Logo> logo, String ifNoneMatch) {
        if (logo.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String etag = "\"" + logo.get().sha256() + "\"";
        var cache = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(logo.get().contentType()))
                .body(logo.get().content());
    }

    record BrandingRequest(@NotBlank @Size(max = 150) String displayName,
                           @Size(max = 200) String addressLine1,
                           @Size(max = 200) String addressLine2,
                           @Size(max = 20) String postalCode,
                           @Size(max = 120) String city,
                           @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
                           @Size(max = 30) String phone,
                           @Email @Size(max = 200) String email,
                           @Size(max = 30) String legalIdentifier,
                           @Pattern(regexp = "^[0-9A-B]{9}$") String finessNumber,
                           @Size(max = 10) String ordonnancierPrefix,
                           @NotBlank @Pattern(regexp = HEX) String primaryColor,
                           @NotBlank @Pattern(regexp = HEX) String secondaryColor,
                           @NotBlank @Pattern(regexp = HEX) String accentColor,
                           @Size(max = 500) String receiptHeader,
                           @Size(max = 500) String receiptFooter) {

        Branding toBranding() {
            return new Branding(displayName, addressLine1, addressLine2, postalCode, city, countryCode, phone, email,
                    legalIdentifier, finessNumber, ordonnancierPrefix, primaryColor, secondaryColor, accentColor,
                    receiptHeader, receiptFooter);
        }
    }
}
