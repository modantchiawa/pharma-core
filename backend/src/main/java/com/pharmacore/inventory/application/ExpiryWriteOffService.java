package com.pharmacore.inventory.application;

import com.pharmacore.inventory.persistence.StockLotEntity;
import com.pharmacore.inventory.persistence.StockLotRepository;
import com.pharmacore.inventory.persistence.StockMovementEntity;
import com.pharmacore.inventory.persistence.StockMovementRepository;
import com.pharmacore.inventory.persistence.StockRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import jakarta.persistence.EntityManager;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expired lots never stay sellable by accident: the FEFO allocator already skips them, this
 * service takes them off the shelf for good with an {@code EXPIRY_WRITE_OFF} movement, so the
 * on-hand figures, the order sheet and the integrity check all agree. The loss is valued at cost
 * in the write-off list; stock is valued by intermittent inventory, so no entry is posted.
 */
@Service
public class ExpiryWriteOffService {

    public record WriteOff(UUID lotId, UUID productId, String designation, String cip13, String lotNumber,
                           LocalDate expiryDate, int quantity, long unitCostCents, long valueCents, Instant writtenOffAt) {
    }

    private final StockRepository stocks;
    private final StockLotRepository lots;
    private final StockMovementRepository movements;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public ExpiryWriteOffService(StockRepository stocks, StockLotRepository lots, StockMovementRepository movements,
                                 TenantCalendar calendar, AuditTrail audit, JdbcClient jdbc,
                                 EntityManager entityManager, Clock clock) {
        this.stocks = stocks;
        this.lots = lots;
        this.movements = movements;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Writes off every lot expired before today (pharmacy time zone); returns the units removed. */
    @Transactional
    public List<WriteOff> writeOffExpired() {
        LocalDate today = calendar.today();
        Instant now = clock.instant();
        UUID userId = TenantContext.actor() instanceof Actor.TenantUser user ? user.userId() : null;
        List<UUID> lotIds = jdbc.sql("""
                        SELECT l.id FROM stock_lot l
                        WHERE l.quantity > 0 AND l.expiry_date < :today
                        ORDER BY l.stock_id, l.id""")
                .param("today", Date.valueOf(today))
                .query(UUID.class).list();
        if (lotIds.isEmpty()) {
            return List.of();
        }
        List<StockLotEntity> expired = lots.findByIdIn(lotIds);
        List<UUID> stockIds = expired.stream().map(StockLotEntity::stockId).distinct().sorted().toList();
        for (UUID stockId : stockIds) {
            stocks.lockById(stockId).ifPresent(stock -> stock.touch(now));
        }
        for (StockLotEntity lot : expired) {
            int units = lot.writeOff();
            if (units == 0) {
                continue;
            }
            movements.save(new StockMovementEntity(lot.stockId(), lot.id(), StockMovementEntity.Type.EXPIRY_WRITE_OFF,
                    -units, "EXPIRY", lot.id(), now, userId));
            audit.record("STOCK_EXPIRY_WRITE_OFF", "STOCK_LOT", lot.id(), Map.of(
                    "lotNumber", lot.lotNumber(), "expiryDate", lot.expiryDate().toString(),
                    "quantity", Integer.toString(units), "valueCents", Long.toString(units * lot.unitCostCents())));
        }
        // The movements live in the persistence context: make them visible to the SQL below.
        entityManager.flush();
        return writeOffs(now, now);
    }

    @Transactional(readOnly = true)
    public List<WriteOff> writeOffs(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT m.lot_id, s.product_id, p.designation, p.cip13, l.lot_number, l.expiry_date,
                               -m.quantity AS quantity, l.unit_cost_cents, m.occurred_at
                        FROM stock_movement m
                        JOIN stock_lot l ON l.id = m.lot_id
                        JOIN stock s ON s.id = m.stock_id
                        JOIN product p ON p.id = s.product_id
                        WHERE m.movement_type = 'EXPIRY_WRITE_OFF' AND m.occurred_at >= :from AND m.occurred_at <= :to
                        ORDER BY m.occurred_at DESC, p.designation""")
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, rowNum) -> {
                    int quantity = rs.getInt("quantity");
                    long cost = rs.getLong("unit_cost_cents");
                    return new WriteOff(rs.getObject("lot_id", UUID.class), rs.getObject("product_id", UUID.class),
                            rs.getString("designation"), rs.getString("cip13"), rs.getString("lot_number"),
                            rs.getDate("expiry_date").toLocalDate(), quantity, cost, quantity * cost,
                            rs.getTimestamp("occurred_at").toInstant());
                })
                .list();
    }
}
