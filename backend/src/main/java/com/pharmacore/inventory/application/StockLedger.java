package com.pharmacore.inventory.application;

import com.pharmacore.inventory.domain.AllocationPlan;
import com.pharmacore.inventory.domain.FefoAllocator;
import com.pharmacore.inventory.domain.InsufficientStockException;
import com.pharmacore.inventory.domain.LotAllocation;
import com.pharmacore.inventory.domain.LotSnapshot;
import com.pharmacore.inventory.domain.ShortfallPolicy;
import com.pharmacore.inventory.persistence.StockEntity;
import com.pharmacore.inventory.persistence.StockLotEntity;
import com.pharmacore.inventory.persistence.StockLotRepository;
import com.pharmacore.inventory.persistence.StockMovementEntity;
import com.pharmacore.inventory.persistence.StockMovementRepository;
import com.pharmacore.inventory.persistence.StockRepository;
import com.pharmacore.shared.context.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for quantity changes. Every operation locks the {@code stock} rows of the
 * products involved ({@code SELECT ... FOR UPDATE}, sorted by product id) before touching lots, so
 * concurrent sales of the same product are serialized and never oversell.
 */
@Service
public class StockLedger {

    private final StockRepository stocks;
    private final StockLotRepository lots;
    private final StockMovementRepository movements;
    private final TenantCalendar calendar;
    private final JdbcClient jdbc;
    private final Clock clock;

    public StockLedger(StockRepository stocks, StockLotRepository lots, StockMovementRepository movements,
                       TenantCalendar calendar, JdbcClient jdbc, Clock clock) {
        this.stocks = stocks;
        this.lots = lots;
        this.movements = movements;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Reference(String type, UUID id, Instant occurredAt, UUID userId) {
    }

    /**
     * Withdraws quantities following FEFO.
     *
     * @return allocation plan per product (lots consumed and uncovered shortfall)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, AllocationPlan> withdraw(Map<UUID, Integer> quantities, ShortfallPolicy policy,
                                              StockMovementEntity.Type movementType, Reference reference) {
        SortedMap<UUID, Integer> sorted = new TreeMap<>(quantities);
        Map<UUID, StockEntity> locked = lock(sorted.keySet());
        Map<UUID, List<StockLotEntity>> lotsByStock = lots.findAvailableByStockIds(
                        locked.values().stream().map(StockEntity::id).toList())
                .stream()
                .collect(Collectors.groupingBy(StockLotEntity::stockId));
        LocalDate today = calendar.today();
        Instant now = clock.instant();

        Map<UUID, AllocationPlan> plans = new TreeMap<>();
        // Plan everything first: with REJECT, one short product aborts the whole operation untouched.
        sorted.forEach((productId, quantity) -> {
            var stock = locked.get(productId);
            List<LotSnapshot> snapshots = lotsByStock.getOrDefault(stock.id(), List.of()).stream()
                    .map(StockLotEntity::snapshot).toList();
            plans.put(productId, FefoAllocator.allocate(productId, snapshots, quantity, stock.reservedQuantity(),
                    today, policy));
        });

        plans.forEach((productId, plan) -> {
            var stock = locked.get(productId);
            Map<UUID, StockLotEntity> stockLots = lotsByStock.getOrDefault(stock.id(), List.of()).stream()
                    .collect(Collectors.toMap(StockLotEntity::id, Function.identity()));
            for (LotAllocation allocation : plan.allocations()) {
                stockLots.get(allocation.lotId()).withdraw(allocation.quantity());
                movements.save(new StockMovementEntity(stock.id(), allocation.lotId(), movementType,
                        -allocation.quantity(), reference.type(), reference.id(), reference.occurredAt(),
                        reference.userId()));
            }
            if (plan.shortfall() > 0) {
                stock.recordShortfall(plan.shortfall(), now);
                movements.save(new StockMovementEntity(stock.id(), null, StockMovementEntity.Type.SALE_SHORTFALL,
                        -plan.shortfall(), reference.type(), reference.id(), reference.occurredAt(),
                        reference.userId()));
            }
            stock.touch(now);
        });
        return plans;
    }

    /** Puts previously withdrawn units back into their original lots (sale cancellation). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(UUID productId, List<LotAllocation> allocations, int shortfall, Reference reference) {
        restore(productId, allocations, shortfall, reference, StockMovementEntity.Type.SALE_CANCELLATION);
    }

    /** Same as {@link #restore(UUID, List, int, Reference)}, recorded under another movement type (return). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(UUID productId, List<LotAllocation> allocations, int shortfall, Reference reference,
                        StockMovementEntity.Type movementType) {
        var stock = lock(List.of(productId)).get(productId);
        Map<UUID, StockLotEntity> lotsById = lots.findByIdIn(allocations.stream().map(LotAllocation::lotId).toList())
                .stream().collect(Collectors.toMap(StockLotEntity::id, Function.identity()));
        Instant now = clock.instant();
        for (LotAllocation allocation : allocations) {
            lotsById.get(allocation.lotId()).restore(allocation.quantity());
            movements.save(new StockMovementEntity(stock.id(), allocation.lotId(), movementType,
                    allocation.quantity(), reference.type(), reference.id(), reference.occurredAt(),
                    reference.userId()));
        }
        if (shortfall > 0) {
            stock.reduceShortfall(Math.min(shortfall, stock.shortfallQuantity()), now);
        }
        stock.touch(now);
    }

    /** Holds units for another pharmacy; they stay physically on the shelf but are no longer sellable. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID productId, int quantity, Reference reference) {
        var stock = lock(List.of(productId)).get(productId);
        int sellable = FefoAllocator.sellableQuantity(lots.findAvailableByStockIds(List.of(stock.id())).stream()
                .map(StockLotEntity::snapshot).toList(), calendar.today());
        int available = sellable - stock.reservedQuantity();
        if (available < quantity) {
            throw new InsufficientStockException(productId, quantity, Math.max(available, 0));
        }
        stock.reserve(quantity, clock.instant());
        movements.save(new StockMovementEntity(stock.id(), null, StockMovementEntity.Type.RESERVATION, quantity,
                reference.type(), reference.id(), reference.occurredAt(), reference.userId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID productId, int quantity, Reference reference) {
        var stock = lock(List.of(productId)).get(productId);
        stock.release(quantity, clock.instant());
        movements.save(new StockMovementEntity(stock.id(), null, StockMovementEntity.Type.RESERVATION_RELEASE,
                -quantity, reference.type(), reference.id(), reference.occurredAt(), reference.userId()));
    }

    /** Converts a reservation into an actual FEFO withdrawal (handover to the other pharmacy). */
    @Transactional(propagation = Propagation.MANDATORY)
    public AllocationPlan consumeReservation(UUID productId, int quantity, Reference reference) {
        release(productId, quantity, reference);
        return withdraw(Map.of(productId, quantity), ShortfallPolicy.REJECT, StockMovementEntity.Type.TRANSFER_OUT,
                reference).get(productId);
    }

    /** Creates missing stock rows (race-free upsert) and locks all of them in product order. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, StockEntity> lock(Collection<UUID> productIds) {
        UUID tenant = TenantContext.requireTenant().value();
        for (UUID productId : productIds.stream().distinct().sorted().toList()) {
            jdbc.sql("""
                            INSERT INTO stock (id, tenant_id, product_id, updated_at)
                            VALUES (:id, :tenant, :product, now())
                            ON CONFLICT (tenant_id, product_id) DO NOTHING""")
                    .param("id", UUID.randomUUID())
                    .param("tenant", tenant)
                    .param("product", productId)
                    .update();
        }
        return stocks.lockByProductIds(productIds).stream()
                .collect(Collectors.toMap(StockEntity::productId, Function.identity()));
    }
}
