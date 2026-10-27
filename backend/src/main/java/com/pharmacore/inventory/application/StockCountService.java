package com.pharmacore.inventory.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pharmacore.control.application.FourEyesService;
import com.pharmacore.control.domain.FourEyesPolicy;
import com.pharmacore.inventory.persistence.StockCountEntity;
import com.pharmacore.inventory.persistence.StockCountLineEntity;
import com.pharmacore.inventory.persistence.StockCountLineRepository;
import com.pharmacore.inventory.persistence.StockCountRepository;
import com.pharmacore.inventory.persistence.StockLotEntity;
import com.pharmacore.inventory.persistence.StockLotRepository;
import com.pharmacore.inventory.persistence.StockMovementEntity;
import com.pharmacore.inventory.persistence.StockMovementRepository;
import com.pharmacore.inventory.persistence.StockRepository;
import com.pharmacore.inventory.persistence.StorageLocationRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Physical inventory ("inventaire tournant") counted blind.
 * <ul>
 *   <li>Starting a count freezes the shelf quantity of every non-empty lot (optionally of one
 *       location). The counter never receives the expected quantity: only a holder of
 *       {@code STOCK_COUNT_VALIDATE} sees it, so the count cannot be "adjusted to match".</li>
 *   <li>Validation applies each counted line's variance (counted − expected) to the lot's
 *       <em>current</em> quantity, so sales made while counting are not undone, and writes one
 *       {@code COUNT_ADJUSTMENT} movement per lot. Uncounted lines leave the lot untouched.</li>
 *   <li>No accounting entry is posted: stock is valued by intermittent inventory at period
 *       closing ({@code accounting_period.closing_stock_cents}), so the variance flows into the
 *       cost of goods sold of the period, which is where SYSCOHADA puts it.</li>
 * </ul>
 */
@Service
public class StockCountService {

    public static final String VALIDATE_PERMISSION = "STOCK_COUNT_VALIDATE";

    private final StockCountRepository counts;
    private final StockCountLineRepository lines;
    private final StockRepository stocks;
    private final StorageLocationRepository locations;
    private final StockLotRepository lots;
    private final StockMovementRepository movements;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final FourEyesService fourEyes;
    private final Clock clock;

    public StockCountService(StockCountRepository counts, StockCountLineRepository lines, StockRepository stocks,
                             StorageLocationRepository locations, StockLotRepository lots,
                             StockMovementRepository movements, AuditTrail audit, JdbcClient jdbc,
                             FourEyesService fourEyes, Clock clock) {
        this.counts = counts;
        this.lines = lines;
        this.stocks = stocks;
        this.locations = locations;
        this.lots = lots;
        this.movements = movements;
        this.audit = audit;
        this.jdbc = jdbc;
        this.fourEyes = fourEyes;
        this.clock = clock;
    }

    /** @param expectedQuantity {@code null} for a counter who may not see it; same for {@code variance} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineView(UUID id, UUID productId, String designation, String cip13, String ean13, String lotNumber,
                           LocalDate expiryDate, Integer expectedQuantity, Integer countedQuantity, Integer variance,
                           Instant countedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CountView(UUID id, String label, String location, StockCountEntity.Status status, Instant startedAt,
                            Instant validatedAt, String notes, int lineCount, int countedLines, Integer varianceUnits,
                            Long varianceValueCents, List<LineView> lines) {
    }

    /** @param locationId counts only the products stored at this location; {@code null} for the whole stock */
    @Transactional
    public CountView start(String label, UUID locationId) {
        return start(label, locationId, null);
    }

    /** A count limited to a few products (the daily rotating micro-count), still blind. */
    @Transactional
    public CountView startForProducts(String label, List<UUID> productIds) {
        if (productIds.isEmpty()) {
            throw new BusinessRuleException("empty-count", "Aucun produit à inventorier");
        }
        return start(label, null, productIds);
    }

    private CountView start(String label, UUID locationId, List<UUID> productIds) {
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        // The count keeps the location as it was named when counted
        String location = locationId == null ? null : locations.findById(locationId)
                .map(l -> l.label().substring(0, Math.min(l.label().length(), 50)))
                .orElseThrow(() -> new NotFoundException("Storage location", locationId));
        // Flushed at once: the lines below reference it through SQL
        var count = counts.saveAndFlush(new StockCountEntity(label.trim(), location, user.userId(), now));
        int frozen = jdbc.sql("""
                        INSERT INTO stock_count_line (id, tenant_id, count_id, stock_id, lot_id, product_id, lot_number,
                                                      expiry_date, expected_quantity)
                        SELECT gen_random_uuid(), s.tenant_id, :count, s.id, l.id, s.product_id, l.lot_number,
                               l.expiry_date, l.quantity
                        FROM stock_lot l
                        JOIN stock s ON s.id = l.stock_id
                        WHERE s.tenant_id = :tenant AND l.quantity > 0
                          AND (:location::uuid IS NULL OR s.location_id = :location::uuid)
                          AND (NOT :scoped OR s.product_id = ANY(:products))""")
                .param("count", count.id())
                .param("tenant", user.tenant().value())
                .param("location", locationId)
                .param("scoped", productIds != null)
                .param("products", productIds == null ? new UUID[0] : productIds.toArray(UUID[]::new))
                .update();
        if (frozen == 0) {
            throw new BusinessRuleException("empty-count", "Aucun lot en stock à inventorier pour ce périmètre");
        }
        audit.record("STOCK_COUNT_STARTED", "STOCK_COUNT", count.id(), Map.of(
                "label", count.label(), "location", String.valueOf(location), "lines", Integer.toString(frozen)));
        return view(count, user);
    }

    @Transactional
    public CountView count(UUID countId, UUID lineId, int quantity) {
        var user = TenantContext.requireTenantUser();
        var count = requireOpen(countId);
        var line = lines.findLine(countId, lineId).orElseThrow(() -> new NotFoundException("Count line", lineId));
        line.count(quantity, user.userId(), clock.instant());
        return view(count, user);
    }

    /** Counts by scanned lot: the line is found by product and lot number. */
    @Transactional
    public CountView countLot(UUID countId, UUID productId, String lotNumber, int quantity) {
        var user = TenantContext.requireTenantUser();
        var count = requireOpen(countId);
        var line = lines.findByCount(countId).stream()
                .filter(l -> l.productId().equals(productId) && l.lotNumber().equalsIgnoreCase(lotNumber.trim()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("lot-not-in-count",
                        "Le lot " + lotNumber + " ne fait pas partie de cet inventaire"));
        line.count(quantity, user.userId(), clock.instant());
        return view(count, user);
    }

    /**
     * Applies the variances. A count that covers a narcotic, or whose variances exceed the
     * four-eyes threshold in value, is signed by a second person (the "double signature" shown in
     * the narcotics register).
     */
    @Transactional
    public CountView validate(UUID countId, String notes, FourEyesService.Credentials approver) {
        var user = TenantContext.requireTenantUser();
        var count = requireOpen(countId);
        Instant now = clock.instant();
        List<StockCountLineEntity> countLines = lines.findByCount(countId);
        boolean narcotic = countsNarcotic(countLines);
        var approval = fourEyes.check(new FourEyesService.Request(FourEyesPolicy.Action.STOCK_COUNT, "STOCK_COUNT",
                countId, absoluteVarianceValue(countLines), narcotic,
                notes == null || notes.isBlank() ? "Validation de l'inventaire " + count.label() : notes, approver));
        List<StockCountLineEntity> adjusted = countLines.stream()
                .filter(l -> l.counted() && l.variance() != 0)
                .toList();
        long varianceValue = 0;
        int varianceUnits = 0;
        if (!adjusted.isEmpty()) {
            // Lock the stock rows in a stable order, then the lots.
            List<UUID> stockIds = adjusted.stream().map(StockCountLineEntity::stockId).distinct().sorted().toList();
            for (UUID stockId : stockIds) {
                stocks.lockById(stockId).orElseThrow(() -> new NotFoundException("Stock", stockId)).touch(now);
            }
            Map<UUID, StockLotEntity> lotsById = lots.findByIdIn(adjusted.stream().map(StockCountLineEntity::lotId).toList())
                    .stream().collect(Collectors.toMap(StockLotEntity::id, Function.identity()));
            for (StockCountLineEntity line : adjusted) {
                var lot = lotsById.get(line.lotId());
                if (lot == null) {
                    throw new NotFoundException("Lot", line.lotId());
                }
                // The variance observed at count time is applied to the live quantity, never below zero.
                int target = Math.max(lot.quantity() + line.variance(), 0);
                int delta = target - lot.quantity();
                if (delta == 0) {
                    continue;
                }
                lot.setQuantity(target);
                movements.save(new StockMovementEntity(line.stockId(), lot.id(),
                        StockMovementEntity.Type.COUNT_ADJUSTMENT, delta, "STOCK_COUNT", countId, now, user.userId()));
                varianceUnits += delta;
                varianceValue += delta * lot.unitCostCents();
            }
        }
        count.validate(user.userId(), blankToNull(notes), now);
        audit.record("STOCK_COUNT_VALIDATED", "STOCK_COUNT", countId, Map.of(
                "label", count.label(),
                "lines", Integer.toString(countLines.size()),
                "counted", Long.toString(countLines.stream().filter(StockCountLineEntity::counted).count()),
                "adjustedLots", Integer.toString(adjusted.size()),
                "varianceUnits", Integer.toString(varianceUnits),
                "varianceValueCents", Long.toString(varianceValue),
                "narcotic", Boolean.toString(narcotic),
                "approvedBy", approval.map(a -> a.approverId().toString()).orElse("")));
        return view(count, user);
    }

    private boolean countsNarcotic(List<StockCountLineEntity> countLines) {
        List<UUID> productIds = countLines.stream().filter(StockCountLineEntity::counted)
                .map(StockCountLineEntity::productId).distinct().toList();
        return !productIds.isEmpty() && jdbc.sql(
                        "SELECT count(*) > 0 FROM product WHERE id = ANY(:ids) AND listing = 'STUPEFIANT'")
                .param("ids", productIds.toArray(UUID[]::new))
                .query(Boolean.class).single();
    }

    /** Sum of the line variances at cost, losses and surpluses alike (they must not cancel out). */
    private long absoluteVarianceValue(List<StockCountLineEntity> countLines) {
        List<StockCountLineEntity> varying = countLines.stream().filter(l -> l.counted() && l.variance() != 0)
                .toList();
        if (varying.isEmpty()) {
            return 0;
        }
        Map<UUID, Long> costByLot = lots.findByIdIn(varying.stream().map(StockCountLineEntity::lotId).toList())
                .stream().collect(Collectors.toMap(StockLotEntity::id, StockLotEntity::unitCostCents));
        return varying.stream()
                .mapToLong(l -> Math.abs((long) l.variance() * costByLot.getOrDefault(l.lotId(), 0L)))
                .sum();
    }

    @Transactional
    public CountView cancel(UUID countId, String reason) {
        var user = TenantContext.requireTenantUser();
        var count = requireOpen(countId);
        count.cancel(user.userId(), reason, clock.instant());
        audit.record("STOCK_COUNT_CANCELLED", "STOCK_COUNT", countId, Map.of("label", count.label(), "reason", reason));
        return view(count, user);
    }

    @Transactional(readOnly = true)
    public CountView get(UUID countId) {
        return view(counts.findById(countId).orElseThrow(() -> new NotFoundException("Stock count", countId)),
                TenantContext.requireTenantUser());
    }

    @Transactional(readOnly = true)
    public List<CountView> recent(int limit) {
        var user = TenantContext.requireTenantUser();
        return counts.findRecent(PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
                .map(count -> summary(count, user))
                .toList();
    }

    private StockCountEntity requireOpen(UUID countId) {
        var count = counts.findById(countId).orElseThrow(() -> new NotFoundException("Stock count", countId));
        if (!count.isOpen()) {
            throw new BusinessRuleException("count-closed", "Cet inventaire est " + count.status());
        }
        return count;
    }

    private CountView summary(StockCountEntity count, Actor.TenantUser user) {
        return toView(count, lines.findByCount(count.id()), user, false);
    }

    private CountView view(StockCountEntity count, Actor.TenantUser user) {
        return toView(count, lines.findByCount(count.id()), user, true);
    }

    private CountView toView(StockCountEntity count, List<StockCountLineEntity> countLines, Actor.TenantUser user,
                             boolean withLines) {
        boolean reveal = user.can(VALIDATE_PERMISSION) || count.status() != StockCountEntity.Status.OPEN;
        Map<UUID, ProductLabel> labels = withLines ? labels(countLines) : Map.of();
        int counted = (int) countLines.stream().filter(StockCountLineEntity::counted).count();
        Integer varianceUnits = reveal ? countLines.stream().mapToInt(StockCountLineEntity::variance).sum() : null;
        Long varianceValue = reveal ? varianceValue(countLines) : null;
        List<LineView> lineViews = withLines ? countLines.stream().map(line -> {
            ProductLabel label = labels.getOrDefault(line.productId(), new ProductLabel("", "", null));
            return new LineView(line.id(), line.productId(), label.designation(), label.cip13(), label.ean13(),
                    line.lotNumber(), line.expiryDate(),
                    reveal ? line.expectedQuantity() : null,
                    line.countedQuantity(),
                    reveal && line.counted() ? line.variance() : null,
                    line.countedAt());
        }).toList() : List.of();
        return new CountView(count.id(), count.label(), count.location(), count.status(), count.startedAt(),
                count.validatedAt(), count.notes(), countLines.size(), counted, varianceUnits, varianceValue, lineViews);
    }

    private Long varianceValue(List<StockCountLineEntity> countLines) {
        List<UUID> lotIds = countLines.stream().filter(l -> l.variance() != 0).map(StockCountLineEntity::lotId).toList();
        if (lotIds.isEmpty()) {
            return 0L;
        }
        Map<UUID, Long> costByLot = lots.findByIdIn(lotIds).stream()
                .collect(Collectors.toMap(StockLotEntity::id, StockLotEntity::unitCostCents));
        return countLines.stream()
                .filter(l -> l.variance() != 0)
                .mapToLong(l -> l.variance() * costByLot.getOrDefault(l.lotId(), 0L))
                .sum();
    }

    private record ProductLabel(String designation, String cip13, String ean13) {
    }

    private Map<UUID, ProductLabel> labels(List<StockCountLineEntity> countLines) {
        List<UUID> productIds = countLines.stream().map(StockCountLineEntity::productId).distinct().toList();
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT id, designation, cip13, ean13 FROM product WHERE id = ANY(:ids)")
                .param("ids", productIds.toArray(UUID[]::new))
                .query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class),
                        new ProductLabel(rs.getString("designation"), rs.getString("cip13"), rs.getString("ean13"))))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
