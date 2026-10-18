package com.pharmacore.catalog.persistence;

import com.pharmacore.catalog.domain.DrugListing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Entry of the national drug catalog, shared by all pharmacies and curated by the platform. */
@Entity
@Table(name = "product")
public class ProductEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false, length = 13)
    private String cip13;

    @Column(length = 13)
    private String ean13;

    @Column(nullable = false)
    private String designation;

    private String dci;

    @Column(name = "pharmaceutical_form", length = 100)
    private String pharmaceuticalForm;

    @Column(length = 100)
    private String dosage;

    @Column(length = 150)
    private String laboratory;

    @Column(name = "atc_code", length = 10)
    private String atcCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DrugListing listing;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "public_price_cents")
    private Long publicPriceCents;

    @Column(name = "reimbursement_rate")
    private Short reimbursementRate;

    @Column(name = "monograph_url", length = 500)
    private String monographUrl;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ProductEntity() {
    }

    public ProductEntity(String cip13, Instant now) {
        this.id = UUID.randomUUID();
        this.cip13 = cip13;
        this.active = true;
        this.updatedAt = now;
    }

    public void describe(ProductData data, Instant now) {
        this.ean13 = data.ean13();
        this.designation = data.designation();
        this.dci = data.dci();
        this.pharmaceuticalForm = data.pharmaceuticalForm();
        this.dosage = data.dosage();
        this.laboratory = data.laboratory();
        this.atcCode = data.atcCode();
        this.listing = data.listing();
        this.vatRate = data.vatRate();
        this.publicPriceCents = data.publicPriceCents();
        this.reimbursementRate = data.reimbursementRate();
        this.monographUrl = data.monographUrl();
        this.active = data.active();
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String cip13() {
        return cip13;
    }

    public String designation() {
        return designation;
    }

    public DrugListing listing() {
        return listing;
    }

    public BigDecimal vatRate() {
        return vatRate;
    }

    public Long publicPriceCents() {
        return publicPriceCents;
    }

    public boolean active() {
        return active;
    }

    public ProductData data() {
        return new ProductData(ean13, designation, dci, pharmaceuticalForm, dosage, laboratory, atcCode, listing,
                vatRate, publicPriceCents, reimbursementRate, monographUrl, active);
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public record ProductData(String ean13,
                              String designation,
                              String dci,
                              String pharmaceuticalForm,
                              String dosage,
                              String laboratory,
                              String atcCode,
                              DrugListing listing,
                              BigDecimal vatRate,
                              Long publicPriceCents,
                              Short reimbursementRate,
                              String monographUrl,
                              boolean active) {
    }
}
