package com.pharmacore.prescription.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "prescription_line")
public class PrescriptionLineEntity {

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

    @Column(length = 200, updatable = false)
    private String posology;

    protected PrescriptionLineEntity() {
    }

    public PrescriptionLineEntity(short lineNo, UUID productId, int quantity, String posology) {
        this.id = UUID.randomUUID();
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = quantity;
        this.posology = posology;
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

    public String posology() {
        return posology;
    }
}
