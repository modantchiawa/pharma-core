package com.pharmacore.network.persistence;

import com.pharmacore.network.domain.TransferParty;
import com.pharmacore.network.domain.TransferStatus;
import com.pharmacore.shared.web.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Reservation of a product by pharmacy A (requester) at pharmacy B (provider). Owned by two
 * tenants, hence no Hibernate tenant discriminator: visibility is enforced by an RLS policy
 * matching either party.
 */
@Entity
@Table(name = "inter_pharmacy_transfer")
public class InterPharmacyTransferEntity {

    @Id
    private UUID id;

    @Column(name = "network_id", nullable = false, updatable = false)
    private UUID networkId;

    @Column(name = "requester_tenant_id", nullable = false, updatable = false)
    private UUID requesterTenantId;

    @Column(name = "provider_tenant_id", nullable = false, updatable = false)
    private UUID providerTenantId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requester_note", length = 500, updatable = false)
    private String requesterNote;

    @Column(name = "provider_note", length = 500)
    private String providerNote;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;

    @Column(name = "reservation_held", nullable = false)
    private boolean reservationHeld;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected InterPharmacyTransferEntity() {
    }

    public InterPharmacyTransferEntity(UUID networkId, UUID requesterTenantId, UUID providerTenantId, UUID productId,
                                       int quantity, UUID requestedBy, String requesterNote, Instant now) {
        if (requesterTenantId.equals(providerTenantId)) {
            throw new BusinessRuleException("self-transfer", "A pharmacy cannot reserve from itself");
        }
        this.id = UUID.randomUUID();
        this.networkId = networkId;
        this.requesterTenantId = requesterTenantId;
        this.providerTenantId = providerTenantId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = TransferStatus.REQUESTED;
        this.requestedBy = requestedBy;
        this.requesterNote = requesterNote;
        this.requestedAt = now;
    }

    public TransferParty partyOf(UUID tenantId) {
        if (requesterTenantId.equals(tenantId)) {
            return TransferParty.REQUESTER;
        }
        if (providerTenantId.equals(tenantId)) {
            return TransferParty.PROVIDER;
        }
        throw new BusinessRuleException("not-a-party", "The pharmacy is not a party to this transfer");
    }

    public void accept(String note, Instant now, Instant expiresAt) {
        moveTo(TransferStatus.ACCEPTED);
        this.providerNote = note;
        this.respondedAt = now;
        this.reservationExpiresAt = expiresAt;
        this.reservationHeld = true;
    }

    public void decline(String note, Instant now) {
        moveTo(TransferStatus.DECLINED);
        this.providerNote = note;
        this.respondedAt = now;
    }

    public void cancel() {
        moveTo(TransferStatus.CANCELLED);
    }

    public void expire() {
        moveTo(TransferStatus.EXPIRED);
    }

    public void complete(Instant now) {
        moveTo(TransferStatus.COMPLETED);
        this.completedAt = now;
        this.reservationHeld = false;
    }

    public void reservationReleased() {
        this.reservationHeld = false;
    }

    private void moveTo(TransferStatus target) {
        if (!status.canMoveTo(target)) {
            throw new BusinessRuleException("invalid-transition", "Transfer cannot go from " + status + " to " + target);
        }
        this.status = target;
    }

    public UUID id() {
        return id;
    }

    public UUID networkId() {
        return networkId;
    }

    public UUID requesterTenantId() {
        return requesterTenantId;
    }

    public UUID providerTenantId() {
        return providerTenantId;
    }

    public UUID productId() {
        return productId;
    }

    public int quantity() {
        return quantity;
    }

    public TransferStatus status() {
        return status;
    }

    public String requesterNote() {
        return requesterNote;
    }

    public String providerNote() {
        return providerNote;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant respondedAt() {
        return respondedAt;
    }

    public Instant reservationExpiresAt() {
        return reservationExpiresAt;
    }

    public boolean reservationHeld() {
        return reservationHeld;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
