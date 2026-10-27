package com.pharmacore.control.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** The "contrôle du jour" of one day: its products are frozen at selection, the count comes later. */
@Entity
@Table(name = "control_daily_count")
public class DailyCountEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "count_date", nullable = false, updatable = false)
    private LocalDate countDate;

    @Column(name = "stock_count_id")
    private UUID stockCountId;

    @Column(name = "selected_at", nullable = false, updatable = false)
    private Instant selectedAt;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Version
    private long version;

    protected DailyCountEntity() {
    }

    public DailyCountEntity(UUID id, LocalDate countDate, Instant selectedAt) {
        this.id = id;
        this.countDate = countDate;
        this.selectedAt = selectedAt;
    }

    public void started(UUID stockCountId, UUID by, Instant now) {
        this.stockCountId = stockCountId;
        this.startedBy = by;
        this.startedAt = now;
    }

    public UUID id() {
        return id;
    }

    public LocalDate countDate() {
        return countDate;
    }

    public UUID stockCountId() {
        return stockCountId;
    }

    public Instant selectedAt() {
        return selectedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }
}
