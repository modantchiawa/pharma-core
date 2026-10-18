package com.pharmacore.network.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "network_membership")
public class NetworkMembershipEntity {

    public enum Status {
        PENDING,
        ACTIVE,
        LEFT
    }

    @EmbeddedId
    private Key key;

    @Column(name = "share_stock", nullable = false)
    private boolean shareStock;

    @Column(nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected NetworkMembershipEntity() {
    }

    public NetworkMembershipEntity(UUID networkId, UUID tenantId, Instant now) {
        this.key = new Key(networkId, tenantId);
        this.shareStock = true;
        this.status = Status.ACTIVE;
        this.joinedAt = now;
    }

    public void shareStock(boolean share) {
        this.shareStock = share;
    }

    public void leave() {
        this.status = Status.LEFT;
    }

    public UUID networkId() {
        return key.networkId;
    }

    public UUID tenantId() {
        return key.tenantId;
    }

    public boolean sharesStock() {
        return shareStock;
    }

    public Status status() {
        return status;
    }

    @Embeddable
    public record Key(@Column(name = "network_id") UUID networkId,
                      @Column(name = "tenant_id") UUID tenantId) implements Serializable {
    }
}
