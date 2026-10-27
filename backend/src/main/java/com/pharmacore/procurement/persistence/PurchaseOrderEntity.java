package com.pharmacore.procurement.persistence;

import com.pharmacore.shared.web.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** The order sheet: drafted from the proposal, adjusted by the pharmacist, sent, then received. */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrderEntity {

    public enum Status {
        DRAFT,
        SENT,
        PARTIALLY_RECEIVED,
        RECEIVED,
        CANCELLED
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(nullable = false, updatable = false, length = 30)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "expected_at")
    private LocalDate expectedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(length = 500)
    private String notes;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    private List<PurchaseOrderLineEntity> lines = new ArrayList<>();

    @Version
    private long version;

    protected PurchaseOrderEntity() {
    }

    public PurchaseOrderEntity(UUID supplierId, String number, UUID createdBy, Instant now, String notes) {
        this.id = UUID.randomUUID();
        this.supplierId = supplierId;
        this.number = number;
        this.status = Status.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.notes = notes;
    }

    /**
     * Replaces the draft's lines. A product already on the order keeps its line (updated in
     * place): deleting and re-inserting it in the same flush would collide on (order, product).
     */
    public void replaceLines(List<PurchaseOrderLineEntity> newLines) {
        requireDraft();
        Map<UUID, PurchaseOrderLineEntity> current = new HashMap<>();
        lines.forEach(line -> current.put(line.productId(), line));
        List<PurchaseOrderLineEntity> kept = new ArrayList<>();
        List<PurchaseOrderLineEntity> added = new ArrayList<>();
        for (PurchaseOrderLineEntity line : newLines) {
            PurchaseOrderLineEntity existing = current.get(line.productId());
            if (existing != null) {
                existing.revise(line);
                kept.add(existing);
            } else {
                added.add(line);
            }
        }
        lines.retainAll(kept);
        lines.addAll(added);
        recomputeTotal();
    }

    public void updateNotes(String notes) {
        requireDraft();
        this.notes = notes;
    }

    public void send(Instant now, LocalDate expectedAt) {
        requireDraft();
        if (lines.isEmpty()) {
            throw new BusinessRuleException("empty-order", "La commande ne contient aucune ligne");
        }
        this.status = Status.SENT;
        this.sentAt = now;
        this.expectedAt = expectedAt;
    }

    public void cancel(Instant now) {
        if (status == Status.RECEIVED || status == Status.CANCELLED) {
            throw new BusinessRuleException("invalid-transition", "Commande déjà " + status);
        }
        this.status = Status.CANCELLED;
        this.closedAt = now;
    }

    /** Records received quantities; the order closes when every line is complete. */
    public void receive(UUID productId, int quantity, Instant now) {
        if (status != Status.SENT && status != Status.PARTIALLY_RECEIVED) {
            throw new BusinessRuleException("invalid-transition", "Commande non envoyée ou déjà clôturée");
        }
        lines.stream().filter(l -> l.productId().equals(productId)).findFirst()
                .ifPresent(line -> line.receive(quantity));
        boolean complete = lines.stream().allMatch(PurchaseOrderLineEntity::complete);
        this.status = complete ? Status.RECEIVED : Status.PARTIALLY_RECEIVED;
        if (complete) {
            this.closedAt = now;
        }
    }

    /** The supplier announced another delivery day. */
    public void reschedule(LocalDate newExpectedAt, LocalDate today) {
        requireOpen();
        if (newExpectedAt.isBefore(today)) {
            throw new BusinessRuleException("expected-in-past", "La nouvelle date de livraison ne peut pas être passée");
        }
        this.expectedAt = newExpectedAt;
    }

    /**
     * Closes an order whose remainder will never be delivered: it counts as received with what
     * actually came, the missing units stay visible on its lines. Returns the units given up.
     */
    public int closeShort(Instant now) {
        requireOpen();
        if (lines.stream().allMatch(line -> line.quantityReceived() == 0)) {
            throw new BusinessRuleException("nothing-received",
                    "Rien n'a encore été reçu sur cette commande : annulez-la plutôt que de la solder");
        }
        int remaining = lines.stream().mapToInt(PurchaseOrderLineEntity::remaining).sum();
        this.status = Status.RECEIVED;
        this.closedAt = now;
        return remaining;
    }

    private void requireOpen() {
        if (status != Status.SENT && status != Status.PARTIALLY_RECEIVED) {
            throw new BusinessRuleException("not-open", "La commande n'est pas en attente de livraison");
        }
    }

    private void requireDraft() {
        if (status != Status.DRAFT) {
            throw new BusinessRuleException("not-draft", "Seule une commande brouillon peut être modifiée");
        }
    }

    private void recomputeTotal() {
        this.totalCents = lines.stream().mapToLong(l -> l.quantityOrdered() * l.unitCostCents()).sum();
    }

    public UUID id() {
        return id;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public UUID supplierId() {
        return supplierId;
    }

    public String number() {
        return number;
    }

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public LocalDate expectedAt() {
        return expectedAt;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public String notes() {
        return notes;
    }

    public long totalCents() {
        return totalCents;
    }

    public List<PurchaseOrderLineEntity> lines() {
        return List.copyOf(lines);
    }
}
