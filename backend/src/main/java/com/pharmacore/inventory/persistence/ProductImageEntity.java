package com.pharmacore.inventory.persistence;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Photo of a product in one pharmacy, reduced on the terminal. Removing the photo empties the
 * row instead of deleting it: the removal is replicated like any update and re-emits the product
 * to the offline POS cache.
 */
@Entity
@Table(name = "product_image")
public class ProductImageEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Basic(fetch = FetchType.LAZY)
    private byte[] content;

    @Column(name = "content_type", length = 20)
    private String contentType;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ProductImageEntity() {
    }

    public ProductImageEntity(UUID productId) {
        this.id = UUID.randomUUID();
        this.productId = productId;
    }

    public void replace(byte[] content, String contentType, String sha256, UUID userId, Instant now) {
        this.content = content.clone();
        this.contentType = contentType;
        this.sizeBytes = content.length;
        this.sha256 = sha256;
        this.updatedBy = userId;
        this.updatedAt = now;
    }

    public void clear(UUID userId, Instant now) {
        this.content = null;
        this.contentType = null;
        this.sizeBytes = null;
        this.sha256 = null;
        this.updatedBy = userId;
        this.updatedAt = now;
    }

    public boolean present() {
        return sha256 != null;
    }

    public UUID productId() {
        return productId;
    }

    public byte[] content() {
        return content == null ? null : content.clone();
    }

    public String contentType() {
        return contentType;
    }

    public Integer sizeBytes() {
        return sizeBytes;
    }

    public String sha256() {
        return sha256;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
