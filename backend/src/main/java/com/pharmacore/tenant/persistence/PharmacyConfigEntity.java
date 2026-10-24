package com.pharmacore.tenant.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** White-label identity and legal mentions of a pharmacy (receipt header, colors, logo...). */
@Entity
@Table(name = "pharmacy_config")
public class PharmacyConfigEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(name = "address_line1", length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(length = 120)
    private String city;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(length = 30)
    private String phone;

    @Column(length = 200)
    private String email;

    @Column(name = "legal_identifier", length = 30)
    private String legalIdentifier;

    @Column(name = "finess_number", length = 9)
    private String finessNumber;

    @Column(name = "ordonnancier_prefix", length = 10)
    private String ordonnancierPrefix;

    @Column(name = "primary_color", nullable = false, length = 7)
    private String primaryColor;

    @Column(name = "secondary_color", nullable = false, length = 7)
    private String secondaryColor;

    @Column(name = "accent_color", nullable = false, length = 7)
    private String accentColor;

    @Column(name = "receipt_header", length = 500)
    private String receiptHeader;

    @Column(name = "receipt_footer", length = 500)
    private String receiptFooter;

    @Column(name = "logo_asset_id")
    private UUID logoAssetId;

    @Column(name = "public_listing", nullable = false)
    private boolean publicListing;

    @Column(name = "public_availability", nullable = false)
    private boolean publicAvailability;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "opening_hours", length = 300)
    private String openingHours;

    @Column(length = 30)
    private String whatsapp;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PharmacyConfigEntity() {
    }

    public PharmacyConfigEntity(String displayName, String countryCode, Instant now) {
        this.id = UUID.randomUUID();
        this.displayName = displayName;
        this.countryCode = countryCode;
        this.primaryColor = "#0F766E";
        this.secondaryColor = "#134E4A";
        this.accentColor = "#F59E0B";
        this.updatedAt = now;
    }

    public void update(Branding branding, Instant now) {
        this.displayName = branding.displayName();
        this.addressLine1 = branding.addressLine1();
        this.addressLine2 = branding.addressLine2();
        this.postalCode = branding.postalCode();
        this.city = branding.city();
        this.countryCode = branding.countryCode();
        this.phone = branding.phone();
        this.email = branding.email();
        this.legalIdentifier = branding.legalIdentifier();
        this.finessNumber = branding.finessNumber();
        this.ordonnancierPrefix = branding.ordonnancierPrefix();
        this.primaryColor = branding.primaryColor();
        this.secondaryColor = branding.secondaryColor();
        this.accentColor = branding.accentColor();
        this.receiptHeader = branding.receiptHeader();
        this.receiptFooter = branding.receiptFooter();
        this.updatedAt = now;
    }

    public void useLogo(UUID assetId, Instant now) {
        this.logoAssetId = assetId;
        this.updatedAt = now;
    }

    /** What the pharmacy chooses to expose in the public directory. */
    public void updatePublicSharing(PublicSharing sharing, Instant now) {
        this.publicListing = sharing.publicListing();
        // Availability can only be published by a listed pharmacy
        this.publicAvailability = sharing.publicListing() && sharing.publicAvailability();
        this.latitude = sharing.latitude();
        this.longitude = sharing.longitude();
        this.openingHours = sharing.openingHours();
        this.whatsapp = sharing.whatsapp();
        this.updatedAt = now;
    }

    public PublicSharing publicSharing() {
        return new PublicSharing(publicListing, publicAvailability, latitude, longitude, openingHours, whatsapp);
    }

    public Branding branding() {
        return new Branding(displayName, addressLine1, addressLine2, postalCode, city, countryCode, phone, email,
                legalIdentifier, finessNumber, ordonnancierPrefix, primaryColor, secondaryColor, accentColor,
                receiptHeader, receiptFooter);
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID logoAssetId() {
        return logoAssetId;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    /** Value object exchanged with the web layer. */
    public record Branding(String displayName,
                           String addressLine1,
                           String addressLine2,
                           String postalCode,
                           String city,
                           String countryCode,
                           String phone,
                           String email,
                           String legalIdentifier,
                           String finessNumber,
                           String ordonnancierPrefix,
                           String primaryColor,
                           String secondaryColor,
                           String accentColor,
                           String receiptHeader,
                           String receiptFooter) {
    }

    /** Public directory settings of the pharmacy. */
    public record PublicSharing(boolean publicListing,
                                boolean publicAvailability,
                                BigDecimal latitude,
                                BigDecimal longitude,
                                String openingHours,
                                String whatsapp) {
    }
}
