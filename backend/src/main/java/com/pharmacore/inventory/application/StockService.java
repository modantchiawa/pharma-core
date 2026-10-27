package com.pharmacore.inventory.application;

import com.pharmacore.control.application.FourEyesService;
import com.pharmacore.control.domain.FourEyesPolicy;
import com.pharmacore.inventory.domain.StorageLocationKind;
import com.pharmacore.inventory.domain.WriteOffKind;
import com.pharmacore.inventory.persistence.StockLotEntity;
import com.pharmacore.inventory.persistence.StockLotRepository;
import com.pharmacore.inventory.persistence.StockMovementEntity;
import com.pharmacore.inventory.persistence.StockMovementRepository;
import com.pharmacore.inventory.persistence.StockRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receipts, inventory adjustments, settings and stock dashboards of the current pharmacy. */
@Service
public class StockService {

    private final StockLedger ledger;
    private final StockRepository stocks;
    private final StockLotRepository lots;
    private final StockMovementRepository movements;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final FourEyesService fourEyes;
    private final Clock clock;

    public StockService(StockLedger ledger, StockRepository stocks, StockLotRepository lots,
                        StockMovementRepository movements, TenantCalendar calendar, AuditTrail audit, JdbcClient jdbc,
                        FourEyesService fourEyes, Clock clock) {
        this.ledger = ledger;
        this.stocks = stocks;
        this.lots = lots;
        this.movements = movements;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.fourEyes = fourEyes;
        this.clock = clock;
    }

    /** A declared exit; {@code approvedBy} is the second person when one signed. */
    public record WriteOff(UUID id, UUID lotId, UUID productId, WriteOffKind kind, int quantity, long valueCents,
                           UUID approvedBy) {
    }

    public record LotView(UUID lotId, String lotNumber, LocalDate expiryDate, int quantity, long unitCostCents) {
    }

    /** Lots of a product; {@code narcotic} and {@code inSafe} tell the dialog that four eyes apply. */
    public record ProductLots(UUID productId, String designation, String cip13, boolean narcotic, boolean inSafe,
                              List<LotView> lots) {
    }

    @Transactional
    public UUID receiveLot(UUID productId, String lotNumber, LocalDate expiryDate, int quantity, long unitCostCents) {
        if (!expiryDate.isAfter(calendar.today())) {
            throw new BusinessRuleException("expired-lot", "Cannot receive a lot that is already expired");
        }
        var stock = ledger.lock(List.of(productId)).get(productId);
        Instant now = clock.instant();
        var lot = lots.findByStockIdAndLotNumberAndExpiryDate(stock.id(), lotNumber, expiryDate)
                .map(existing -> {
                    existing.add(quantity, unitCostCents);
                    return existing;
                })
                .orElseGet(() -> lots.save(new StockLotEntity(stock.id(), lotNumber, expiryDate, quantity,
                        unitCostCents, now)));
        stock.touch(now);
        UUID userId = TenantContext.requireTenantUser().userId();
        movements.save(new StockMovementEntity(stock.id(), lot.id(), StockMovementEntity.Type.RECEIPT, quantity,
                "RECEIPT", lot.id(), now, userId));
        audit.record("STOCK_RECEIVED", "STOCK_LOT", lot.id(), Map.of(
                "productId", productId.toString(), "lotNumber", lotNumber, "expiryDate", expiryDate.toString(),
                "quantity", Integer.toString(quantity)));
        return lot.id();
    }

    /**
     * Corrects a lot to the quantity found on the shelf. Above the pharmacy's threshold (value at
     * cost of the difference), or for a narcotic, a second person signs with their PIN.
     */
    @Transactional
    public void adjustLot(UUID lotId, int countedQuantity, String reason, FourEyesService.Credentials approver) {
        var lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Lot", lotId));
        var stock = stocks.lockById(lot.stockId()).orElseThrow(() -> new NotFoundException("Stock", lot.stockId()));
        int delta = countedQuantity - lot.quantity();
        if (delta == 0) {
            return;
        }
        long value = (long) delta * lot.unitCostCents();
        var approval = fourEyes.check(new FourEyesService.Request(FourEyesPolicy.Action.STOCK_ADJUSTMENT, "STOCK_LOT",
                lotId, value, isNarcotic(stock.productId()), reason, approver));
        Instant now = clock.instant();
        lot.setQuantity(countedQuantity);
        stock.touch(now);
        movements.save(new StockMovementEntity(stock.id(), lotId, StockMovementEntity.Type.ADJUSTMENT, delta,
                "ADJUSTMENT", lotId, now, TenantContext.requireTenantUser().userId()));
        audit.record("STOCK_ADJUSTED", "STOCK_LOT", lotId, Map.of(
                "delta", Integer.toString(delta), "counted", Integer.toString(countedQuantity), "reason", reason,
                "productId", stock.productId().toString(), "valueCents", Long.toString(value),
                "approvedBy", approval.map(a -> a.approverId().toString()).orElse("")));
    }

    /**
     * Declared exit of units that will never be sold: breakage, expiry noticed on the shelf, loss
     * or theft. Valued at the lot's cost; four eyes above the threshold and for narcotics.
     */
    @Transactional
    public WriteOff writeOff(UUID lotId, WriteOffKind kind, int quantity, String reason,
                             FourEyesService.Credentials approver) {
        var lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("Lot", lotId));
        var stock = stocks.lockById(lot.stockId()).orElseThrow(() -> new NotFoundException("Stock", lot.stockId()));
        if (quantity <= 0 || quantity > lot.quantity()) {
            throw new BusinessRuleException("write-off-quantity",
                    "Le lot " + lot.lotNumber() + " contient " + lot.quantity() + " unité(s)");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("reason-required", "Le motif est obligatoire");
        }
        long value = (long) quantity * lot.unitCostCents();
        UUID id = UUID.randomUUID();
        var approval = fourEyes.check(new FourEyesService.Request(FourEyesPolicy.Action.STOCK_WRITE_OFF,
                "STOCK_WRITE_OFF", id, value, isNarcotic(stock.productId()), reason, approver));
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        lot.setQuantity(lot.quantity() - quantity);
        stock.touch(now);
        jdbc.sql("""
                        INSERT INTO stock_write_off (id, tenant_id, lot_id, product_id, kind, quantity, unit_cost_cents,
                                                     value_cents, reason, recorded_by, approval_id, occurred_at)
                        VALUES (:id, :tenant, :lot, :product, :kind, :quantity, :cost, :value, :reason, :by,
                                :approval, :at)""")
                .param("id", id)
                .param("tenant", user.tenant().value())
                .param("lot", lotId)
                .param("product", stock.productId())
                .param("kind", kind.name())
                .param("quantity", quantity)
                .param("cost", lot.unitCostCents())
                .param("value", value)
                .param("reason", reason.trim())
                .param("by", user.userId())
                .param("approval", approval.map(FourEyesService.Approval::id).orElse(null))
                .param("at", java.sql.Timestamp.from(now))
                .update();
        movements.save(new StockMovementEntity(stock.id(), lotId, StockMovementEntity.Type.WRITE_OFF, -quantity,
                "WRITE_OFF", id, now, user.userId()));
        audit.record("STOCK_WRITTEN_OFF", "STOCK_LOT", lotId, Map.of(
                "writeOffId", id.toString(), "kind", kind.name(), "quantity", Integer.toString(quantity),
                "valueCents", Long.toString(value), "reason", reason.trim(), "productId", stock.productId().toString(),
                "lotNumber", lot.lotNumber(),
                "approvedBy", approval.map(a -> a.approverId().toString()).orElse("")));
        return new WriteOff(id, lotId, stock.productId(), kind, quantity, value,
                approval.map(FourEyesService.Approval::approverId).orElse(null));
    }

    /** Lots on the shelf of one product, for the exit and adjustment dialog. */
    @Transactional(readOnly = true)
    public ProductLots lotsOf(UUID productId) {
        var product = jdbc.sql("""
                        SELECT p.designation, p.cip13, p.listing, loc.kind AS location_kind
                        FROM product p
                        LEFT JOIN stock s ON s.product_id = p.id
                        LEFT JOIN storage_location loc ON loc.id = s.location_id
                        WHERE p.id = :product""")
                .param("product", productId)
                .query((rs, rowNum) -> new ProductLots(productId, rs.getString("designation"), rs.getString("cip13"),
                        "STUPEFIANT".equals(rs.getString("listing")), "SAFE".equals(rs.getString("location_kind")),
                        List.of()))
                .optional()
                .orElseThrow(() -> new NotFoundException("Product", productId));
        List<LotView> lotViews = jdbc.sql("""
                        SELECT l.id, l.lot_number, l.expiry_date, l.quantity, l.unit_cost_cents
                        FROM stock_lot l JOIN stock s ON s.id = l.stock_id
                        WHERE s.product_id = :product AND l.quantity > 0
                        ORDER BY l.expiry_date, l.lot_number""")
                .param("product", productId)
                .query((rs, rowNum) -> new LotView(rs.getObject("id", UUID.class), rs.getString("lot_number"),
                        rs.getDate("expiry_date").toLocalDate(), rs.getInt("quantity"), rs.getLong("unit_cost_cents")))
                .list();
        return new ProductLots(productId, product.designation(), product.cip13(), product.narcotic(),
                product.inSafe(), lotViews);
    }

    private boolean isNarcotic(UUID productId) {
        return jdbc.sql("SELECT listing = 'STUPEFIANT' FROM product WHERE id = :id")
                .param("id", productId).query(Boolean.class).optional().orElse(false);
    }

    /** Price update at goods receipt (the wholesaler's invoice carries the new public price). */
    @Transactional
    public void updateSellingPrice(UUID productId, long sellingPriceCents) {
        var stock = ledger.lock(List.of(productId)).get(productId);
        Long previous = stock.sellingPriceCents();
        if (previous != null && previous == sellingPriceCents) {
            return;
        }
        stock.configure(sellingPriceCents, stock.reorderThreshold(), stock.reorderQuantity(), clock.instant());
        audit.record("STOCK_PRICE_UPDATED", "STOCK", stock.id(), Map.of(
                "productId", productId.toString(),
                "previousPriceCents", String.valueOf(previous),
                "priceCents", Long.toString(sellingPriceCents)));
    }

    @Transactional
    public void resolveShortfall(UUID productId, String reason) {
        var stock = ledger.lock(List.of(productId)).get(productId);
        int cleared = stock.clearShortfall(clock.instant());
        audit.record("STOCK_SHORTFALL_RESOLVED", "STOCK", stock.id(), Map.of(
                "productId", productId.toString(), "cleared", Integer.toString(cleared), "reason", reason));
    }

    @Transactional
    public void configure(UUID productId, Long sellingPriceCents, int reorderThreshold, int reorderQuantity) {
        var stock = ledger.lock(List.of(productId)).get(productId);
        Long previousPrice = stock.sellingPriceCents();
        stock.configure(sellingPriceCents, reorderThreshold, reorderQuantity, clock.instant());
        audit.record("STOCK_CONFIGURED", "STOCK", stock.id(), Map.of(
                "productId", productId.toString(),
                "previousPriceCents", String.valueOf(previousPrice),
                "priceCents", String.valueOf(sellingPriceCents),
                "reorderThreshold", Integer.toString(reorderThreshold),
                "reorderQuantity", Integer.toString(reorderQuantity)));
    }

    /** Which products to list by placement: all, those stored at one location, or those stored nowhere. */
    public sealed interface LocationFilter {

        record Any() implements LocationFilter {
        }

        record At(UUID locationId) implements LocationFilter {
        }

        record Unassigned() implements LocationFilter {
        }
    }

    @Transactional(readOnly = true)
    public List<StockOverview> overview(String search, boolean onlyBelowThreshold, int limit, int offset) {
        return overview(search, onlyBelowThreshold, new LocationFilter.Any(), limit, offset);
    }

    @Transactional(readOnly = true)
    public List<StockOverview> overview(String search, boolean onlyBelowThreshold, LocationFilter location, int limit,
                                        int offset) {
        return jdbc.sql("""
                        SELECT s.product_id, p.cip13, p.designation, s.selling_price_cents,
                               p.public_price_cents, s.reorder_threshold, s.reorder_quantity, s.reserved_quantity,
                               s.shortfall_quantity,
                               coalesce(l.on_hand, 0) AS on_hand,
                               coalesce(l.sellable, 0) AS sellable,
                               l.next_expiry,
                               s.location_id, loc.code AS location_code, loc.name AS location_name,
                               loc.kind AS location_kind, s.location_detail
                        FROM stock s
                        JOIN product p ON p.id = s.product_id
                        LEFT JOIN storage_location loc ON loc.id = s.location_id
                        LEFT JOIN LATERAL (
                            SELECT sum(quantity) AS on_hand,
                                   sum(quantity) FILTER (WHERE expiry_date > :today) AS sellable,
                                   min(expiry_date) FILTER (WHERE expiry_date > :today) AS next_expiry
                            FROM stock_lot WHERE stock_id = s.id AND quantity > 0
                        ) l ON true
                        WHERE (:search = '' OR p.designation ILIKE '%' || :search || '%' OR p.cip13 = :search)
                          AND (NOT :below OR coalesce(l.sellable, 0) - s.reserved_quantity <= s.reorder_threshold)
                          AND (:locationMode = 'ANY'
                               OR (:locationMode = 'AT' AND s.location_id = :locationId::uuid)
                               OR (:locationMode = 'NONE' AND s.location_id IS NULL))
                        ORDER BY p.designation
                        LIMIT :limit OFFSET :offset""")
                .param("today", Date.valueOf(calendar.today()))
                .param("locationMode", switch (location) {
                    case LocationFilter.Any _ -> "ANY";
                    case LocationFilter.At _ -> "AT";
                    case LocationFilter.Unassigned _ -> "NONE";
                })
                .param("locationId", location instanceof LocationFilter.At at ? at.locationId() : null)
                .param("search", search == null ? "" : search.trim())
                .param("below", onlyBelowThreshold)
                .param("limit", Math.clamp(limit, 1, 500))
                .param("offset", Math.max(offset, 0))
                .query((rs, rowNum) -> {
                    int sellable = rs.getInt("sellable");
                    int reserved = rs.getInt("reserved_quantity");
                    int threshold = rs.getInt("reorder_threshold");
                    Date nextExpiry = rs.getDate("next_expiry");
                    UUID locationId = rs.getObject("location_id", UUID.class);
                    return new StockOverview(
                            rs.getObject("product_id", UUID.class),
                            rs.getString("cip13"),
                            rs.getString("designation"),
                            locationId == null ? null : new StockLocation(locationId, rs.getString("location_code"),
                                    rs.getString("location_name"),
                                    StorageLocationKind.valueOf(rs.getString("location_kind")),
                                    rs.getString("location_detail")),
                            (Long) rs.getObject("selling_price_cents"),
                            (Long) rs.getObject("public_price_cents"),
                            rs.getInt("on_hand"),
                            Math.max(sellable - reserved, 0),
                            reserved,
                            rs.getInt("shortfall_quantity"),
                            threshold,
                            rs.getInt("reorder_quantity"),
                            nextExpiry == null ? null : nextExpiry.toLocalDate(),
                            sellable - reserved <= threshold);
                })
                .list();
    }

    /** Lots still on the shelf that expire within the given horizon (or are already expired). */
    @Transactional(readOnly = true)
    public List<ExpiryAlert> expiryAlerts(int withinDays) {
        LocalDate today = calendar.today();
        return jdbc.sql("""
                        SELECT l.id, p.id AS product_id, p.cip13, p.designation, l.lot_number, l.expiry_date, l.quantity
                        FROM stock_lot l
                        JOIN stock s ON s.id = l.stock_id
                        JOIN product p ON p.id = s.product_id
                        WHERE l.quantity > 0 AND l.expiry_date <= :horizon
                        ORDER BY l.expiry_date, p.designation""")
                .param("horizon", Date.valueOf(today.plusDays(Math.clamp(withinDays, 0, 730))))
                .query((rs, rowNum) -> {
                    LocalDate expiry = rs.getDate("expiry_date").toLocalDate();
                    return new ExpiryAlert(
                            rs.getObject("id", UUID.class),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("cip13"),
                            rs.getString("designation"),
                            rs.getString("lot_number"),
                            expiry,
                            rs.getInt("quantity"),
                            !expiry.isAfter(today));
                })
                .list();
    }

    /**
     * Automatic replenishment proposal: products whose sellable stock fell to or below the
     * threshold. Suggested quantity tops up to threshold + reorder quantity and covers the
     * shortfall left by offline sales.
     */
    @Transactional(readOnly = true)
    public List<ReorderSuggestion> reorderSuggestions() {
        return overview(null, true, 500, 0).stream()
                .filter(item -> item.reorderThreshold() > 0 || item.shortfall() > 0)
                .map(item -> new ReorderSuggestion(item.productId(), item.cip13(), item.designation(),
                        item.available(), item.reorderThreshold(),
                        Math.max(item.reorderThreshold() + item.reorderQuantity() - item.available(), 0)
                                + item.shortfall()))
                .filter(suggestion -> suggestion.suggestedQuantity() > 0)
                .toList();
    }

    /** Where the product is stored, as shown in the stock lists, the POS and on shelf labels. */
    public record StockLocation(UUID id, String code, String name, StorageLocationKind kind, String detail) {
    }

    public record StockOverview(UUID productId, String cip13, String designation, StockLocation location,
                                Long sellingPriceCents, Long publicPriceCents, int onHand, int available,
                                int reserved, int shortfall, int reorderThreshold, int reorderQuantity,
                                LocalDate nextExpiry, boolean belowThreshold) {
    }

    public record ExpiryAlert(UUID lotId, UUID productId, String cip13, String designation, String lotNumber,
                              LocalDate expiryDate, int quantity, boolean expired) {
    }

    public record ReorderSuggestion(UUID productId, String cip13, String designation, int available,
                                    int reorderThreshold, int suggestedQuantity) {
    }
}
