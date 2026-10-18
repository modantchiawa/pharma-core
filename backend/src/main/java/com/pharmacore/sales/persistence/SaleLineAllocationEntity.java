package com.pharmacore.sales.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "sale_line_allocation")
public class SaleLineAllocationEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "lot_id", nullable = false, updatable = false)
    private UUID lotId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    protected SaleLineAllocationEntity() {
    }

    SaleLineAllocationEntity(UUID lotId, int quantity) {
        this.id = UUID.randomUUID();
        this.lotId = lotId;
        this.quantity = quantity;
    }

    UUID lotId() {
        return lotId;
    }

    int quantity() {
        return quantity;
    }
}
