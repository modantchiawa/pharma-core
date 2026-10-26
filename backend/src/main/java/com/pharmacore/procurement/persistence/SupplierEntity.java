package com.pharmacore.procurement.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Wholesaler or laboratory the pharmacy orders from. */
@Entity
@Table(name = "supplier")
public class SupplierEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "contact_name", length = 150)
    private String contactName;

    @Column(length = 30)
    private String phone;

    @Column(length = 30)
    private String whatsapp;

    @Column(length = 200)
    private String email;

    @Column(length = 300)
    private String address;

    @Column(name = "lead_time_days", nullable = false)
    private short leadTimeDays;

    @Column(name = "order_days", length = 30)
    private String orderDays;

    @Column(name = "min_order_cents", nullable = false)
    private long minOrderCents;

    @Column(name = "payment_terms", length = 100)
    private String paymentTerms;

    @Column(nullable = false)
    private boolean active;

    @Column(length = 500)
    private String notes;

    @Version
    private long version;

    protected SupplierEntity() {
    }

    public SupplierEntity(String code, Details details) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.active = true;
        update(details);
    }

    public final void update(Details details) {
        this.name = details.name();
        this.contactName = details.contactName();
        this.phone = details.phone();
        this.whatsapp = details.whatsapp();
        this.email = details.email();
        this.address = details.address();
        this.leadTimeDays = (short) details.leadTimeDays();
        this.orderDays = details.orderDays();
        this.minOrderCents = details.minOrderCents();
        this.paymentTerms = details.paymentTerms();
        this.notes = details.notes();
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public int leadTimeDays() {
        return leadTimeDays;
    }

    public long minOrderCents() {
        return minOrderCents;
    }

    public boolean active() {
        return active;
    }

    public Details details() {
        return new Details(name, contactName, phone, whatsapp, email, address, leadTimeDays, orderDays, minOrderCents,
                paymentTerms, notes);
    }

    public record Details(String name, String contactName, String phone, String whatsapp, String email, String address,
                          int leadTimeDays, String orderDays, long minOrderCents, String paymentTerms, String notes) {
    }
}
