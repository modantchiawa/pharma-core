package com.pharmacore.tenant.persistence;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "tenant_asset")
public class TenantAssetEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] content;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantAssetEntity() {
    }

    public TenantAssetEntity(String kind, String contentType, byte[] content, String sha256, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.contentType = contentType;
        this.content = content.clone();
        this.sha256 = sha256;
        this.sizeBytes = content.length;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] content() {
        return content.clone();
    }

    public String sha256() {
        return sha256;
    }
}
