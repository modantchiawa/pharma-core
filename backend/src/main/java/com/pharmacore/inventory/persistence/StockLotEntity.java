package com.pharmacore.inventory.persistence;

import com.pharmacore.inventory.domain.LotSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "stock_lot")
public class StockLotEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "stock_id", nullable = false, updatable = false)
    private UUID stockId;

    @Column(name = "lot_number", nullable = false, updatable = false, length = 40)
    private String lotNumber;

    @Column(name = "expiry_date", nullable = false, updatable = false)
    private LocalDate expiryDate;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_cost_cents", nullable = false)
    private long unitCostCents;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Version
    private long version;

    protected StockLotEntity() {
    }

    public StockLotEntity(UUID stockId, String lotNumber, LocalDate expiryDate, int quantity, long unitCostCents,
                          Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.stockId = stockId;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.quantity = quantity;
        this.unitCostCents = unitCostCents;
        this.receivedAt = receivedAt;
    }

    public void add(int units, long unitCostCents) {
        // Weighted average cost keeps margin reports correct when a lot is received in several parcels.
        long totalCost = this.unitCostCents * this.quantity + unitCostCents * units;
        this.quantity += units;
        this.unitCostCents = this.quantity == 0 ? unitCostCents : Math.round((double) totalCost / this.quantity);
    }

    public void withdraw(int units) {
        if (units > quantity) {
            throw new IllegalStateException("Lot " + lotNumber + " holds only " + quantity + " units");
        }
        this.quantity -= units;
    }

    public void restore(int units) {
        this.quantity += units;
    }

    public void setQuantity(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        this.quantity = quantity;
    }

    public LotSnapshot snapshot() {
        return new LotSnapshot(id, lotNumber, expiryDate, quantity);
    }

    public UUID id() {
        return id;
    }

    public UUID stockId() {
        return stockId;
    }

    public String lotNumber() {
        return lotNumber;
    }

    public LocalDate expiryDate() {
        return expiryDate;
    }

    public int quantity() {
        return quantity;
    }

    public long unitCostCents() {
        return unitCostCents;
    }

    /** Takes the whole remaining quantity off the shelf (expired lot); returns the units written off. */
    public int writeOff() {
        int units = quantity;
        this.quantity = 0;
        return units;
    }
}
