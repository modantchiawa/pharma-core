package com.pharmacore.sales.persistence;

import com.pharmacore.inventory.domain.LotAllocation;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sale_line")
public class SaleLineEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false, updatable = false)
    private long unitPriceCents;

    @Column(name = "discount_cents", nullable = false, updatable = false)
    private long discountCents;

    @Column(name = "net_cents", nullable = false, updatable = false)
    private long netCents;

    @Column(name = "vat_rate", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal vatRate;

    /** Posology typed at the counter, as JSON (see {@code Posology}); null when none was given. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "posology", updatable = false)
    private String posology;

    /** FEFO lots the units were taken from: batch traceability in case of a drug recall. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "sale_line_id", nullable = false, updatable = false)
    private List<SaleLineAllocationEntity> allocations = new ArrayList<>();

    protected SaleLineEntity() {
    }

    public SaleLineEntity(short lineNo, UUID productId, int quantity, long unitPriceCents, long discountCents,
                          BigDecimal vatRate, List<LotAllocation> lotAllocations) {
        this(lineNo, productId, quantity, unitPriceCents, discountCents, vatRate, lotAllocations, null);
    }

    public SaleLineEntity(short lineNo, UUID productId, int quantity, long unitPriceCents, long discountCents,
                          BigDecimal vatRate, List<LotAllocation> lotAllocations, String posologyJson) {
        this.id = UUID.randomUUID();
        this.posology = posologyJson;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
        this.discountCents = discountCents;
        this.netCents = unitPriceCents * quantity - discountCents;
        this.vatRate = vatRate;
        lotAllocations.forEach(allocation ->
                allocations.add(new SaleLineAllocationEntity(allocation.lotId(), allocation.quantity())));
    }

    public List<LotAllocation> lotAllocations() {
        return allocations.stream().map(a -> new LotAllocation(a.lotId(), a.quantity())).toList();
    }

    /** Units sold that no lot could cover (offline replay). */
    public int uncoveredQuantity() {
        return quantity - allocations.stream().mapToInt(SaleLineAllocationEntity::quantity).sum();
    }

    public UUID id() {
        return id;
    }

    public short lineNo() {
        return lineNo;
    }

    public UUID productId() {
        return productId;
    }

    public int quantity() {
        return quantity;
    }

    public long unitPriceCents() {
        return unitPriceCents;
    }

    public long discountCents() {
        return discountCents;
    }

    public long netCents() {
        return netCents;
    }

    public BigDecimal vatRate() {
        return vatRate;
    }

    public String posologyJson() {
        return posology;
    }
}
