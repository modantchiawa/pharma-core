package com.pharmacore.procurement.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.accounting.domain.VatSplit;
import com.pharmacore.control.application.ReceivingControlService;
import com.pharmacore.inventory.application.StockService;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.inventory.domain.ProductImageRules;
import com.pharmacore.procurement.domain.OrderGrouping;
import com.pharmacore.procurement.domain.OrderProposalCalculator;
import com.pharmacore.procurement.domain.OrderProposalCalculator.Input;
import com.pharmacore.procurement.domain.OrderProposalCalculator.Proposal;
import com.pharmacore.procurement.domain.OrderTracking;
import com.pharmacore.procurement.persistence.PurchaseOrderRepository;
import com.pharmacore.procurement.persistence.SupplierProductRepository;
import com.pharmacore.procurement.persistence.SupplierRepository;
import com.pharmacore.procurement.persistence.PurchaseOrderEntity;
import com.pharmacore.procurement.persistence.PurchaseOrderLineEntity;
import com.pharmacore.procurement.persistence.SupplierEntity;
import com.pharmacore.procurement.persistence.SupplierProductEntity;
import com.pharmacore.sales.persistence.TenantSequenceRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suppliers, order proposals ("fiche de commande du lendemain"), purchase orders and deliveries. */
@Service
public class ProcurementService {

    public static final int VELOCITY_PERIOD_DAYS = 28;
    static final String ORDER_SEQUENCE = "PURCHASE_ORDER";
    static final String SUPPLIER_SEQUENCE = "SUPPLIER";
    private static final DateTimeFormatter FRENCH_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SupplierRepository suppliers;
    private final SupplierProductRepository supplierProducts;
    private final PurchaseOrderRepository orders;
    private final StockService stock;
    private final AccountingPoster accounting;
    private final TenantSequenceRepository sequences;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final ReceivingControlService receivingControl;
    private final Clock clock;

    public ProcurementService(SupplierRepository suppliers, SupplierProductRepository supplierProducts,
                              PurchaseOrderRepository orders,
                              StockService stock, AccountingPoster accounting, TenantSequenceRepository sequences,
                              TenantCalendar calendar, AuditTrail audit, JdbcClient jdbc, EntityManager entityManager,
                              ReceivingControlService receivingControl, Clock clock) {
        this.entityManager = entityManager;
        this.receivingControl = receivingControl;
        this.suppliers = suppliers;
        this.supplierProducts = supplierProducts;
        this.orders = orders;
        this.stock = stock;
        this.accounting = accounting;
        this.sequences = sequences;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ suppliers

    public record SupplierView(UUID id, String code, SupplierEntity.Details details, boolean active) {

        static SupplierView of(SupplierEntity s) {
            return new SupplierView(s.id(), s.code(), s.details(), s.active());
        }
    }

    public record SupplierProductView(UUID id, UUID supplierId, UUID productId, String designation, String cip13,
                                      String supplierReference, Long purchasePriceCents, int packSize, boolean preferred) {
    }

    @Transactional(readOnly = true)
    public List<SupplierView> suppliers() {
        return suppliers.findActive().stream().map(SupplierView::of).toList();
    }

    @Transactional
    public SupplierView createSupplier(SupplierEntity.Details details) {
        String code = "F" + String.format("%04d", sequences.next(SUPPLIER_SEQUENCE));
        var supplier = suppliers.save(new SupplierEntity(code, details));
        audit.record("SUPPLIER_CREATED", "SUPPLIER", supplier.id(), Map.of("name", details.name()));
        return SupplierView.of(supplier);
    }

    @Transactional
    public SupplierView updateSupplier(UUID id, SupplierEntity.Details details) {
        var supplier = suppliers.findById(id).orElseThrow(() -> new NotFoundException("Supplier", id));
        supplier.update(details);
        return SupplierView.of(supplier);
    }

    @Transactional
    public void linkProduct(UUID supplierId, UUID productId, String reference, Long priceCents, int packSize,
                            boolean preferred) {
        suppliers.findById(supplierId).orElseThrow(() -> new NotFoundException("Supplier", supplierId));
        if (preferred) {
            supplierProducts.findByProductId(productId).forEach(other -> other.update(other.supplierReference(),
                    other.purchasePriceCents(), other.packSize(), false));
        }
        supplierProducts.findBySupplierIdAndProductId(supplierId, productId)
                .ifPresentOrElse(link -> link.update(reference, priceCents, packSize, preferred),
                        () -> supplierProducts.save(new SupplierProductEntity(supplierId, productId, reference,
                                priceCents, packSize, preferred)));
    }

    @Transactional(readOnly = true)
    public List<SupplierProductView> supplierCatalog(UUID supplierId) {
        return jdbc.sql("""
                        SELECT sp.id, sp.supplier_id, sp.product_id, p.designation, p.cip13, sp.supplier_reference,
                               sp.purchase_price_cents, sp.pack_size, sp.preferred
                        FROM supplier_product sp JOIN product p ON p.id = sp.product_id
                        WHERE sp.supplier_id = :supplier ORDER BY p.designation""")
                .param("supplier", supplierId)
                .query((rs, rowNum) -> new SupplierProductView(rs.getObject("id", UUID.class),
                        rs.getObject("supplier_id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("designation"), rs.getString("cip13"), rs.getString("supplier_reference"),
                        (Long) rs.getObject("purchase_price_cents"), rs.getInt("pack_size"), rs.getBoolean("preferred")))
                .list();
    }

    // ------------------------------------------------------------------ proposal

    /** @param seasonalFactor the coming weeks last year against the yearly average (1 = unknown or flat) */
    public record ProposalLine(UUID productId, String cip13, String designation, UUID supplierId, String supplierName,
                               int available, int onOrder, int expiringSoon, long soldLastPeriod, long shortfall,
                               double dailyDemand, int targetStock, int suggestedQuantity, int packSize,
                               Long purchasePriceCents, List<OrderProposalCalculator.Reason> reasons,
                               double seasonalFactor) {
    }

    /** The coming weeks, one year ago, compared with the twelve months around them. */
    static final int SEASON_WINDOW_DAYS = 35;
    /** Below this many units sold over the reference year, no seasonality is inferred. */
    static final int SEASON_MIN_YEAR_UNITS = 24;

    public record ProposalSheet(LocalDate date, int periodDays, List<ProposalLine> lines, long estimatedTotalCents) {
    }

    /**
     * Daily rate of the coming weeks last year over the daily rate of the year around them,
     * rounded to a tenth; 1 when last year is too thin to tell.
     */
    static double seasonalFactor(long windowUnits, long yearUnits) {
        if (yearUnits < SEASON_MIN_YEAR_UNITS) {
            return 1.0;
        }
        double windowRate = windowUnits / (double) SEASON_WINDOW_DAYS;
        double yearRate = yearUnits / 365.0;
        return Math.round(windowRate / yearRate * 10.0) / 10.0;
    }

    /**
     * Tomorrow's order sheet. One line per product needing replenishment, routed to its
     * preferred supplier (or left unassigned). {@code supplierId} restricts to one supplier.
     */
    @Transactional(readOnly = true)
    public ProposalSheet proposal(UUID supplierId) {
        LocalDate today = calendar.today();
        Instant periodStart = today.minusDays(VELOCITY_PERIOD_DAYS).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        LocalDate windowStart = today.plusDays(1).minusYears(1);
        Instant seasonStart = windowStart.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant seasonEnd = windowStart.plusDays(SEASON_WINDOW_DAYS).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant yearStart = windowStart.minusDays(182).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant yearEnd = windowStart.plusDays(183).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<ProposalLine> lines = jdbc.sql("""
                        WITH pref AS (
                            SELECT DISTINCT ON (sp.product_id) sp.product_id, sp.supplier_id, s.name AS supplier_name,
                                   s.lead_time_days, sp.pack_size, sp.purchase_price_cents
                            FROM supplier_product sp JOIN supplier s ON s.id = sp.supplier_id AND s.active
                            ORDER BY sp.product_id, sp.preferred DESC, sp.last_purchase_at DESC NULLS LAST
                        ),
                        sold AS (
                            SELECT l.product_id, sum(l.quantity) AS qty
                            FROM sale_line l JOIN sale s ON s.id = l.sale_id
                            WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED' AND s.occurred_at >= :since
                            GROUP BY l.product_id
                        ),
                        lost AS (
                            SELECT st.product_id, -sum(m.quantity) AS qty
                            FROM stock_movement m JOIN stock st ON st.id = m.stock_id
                            WHERE m.tenant_id = :tenant AND m.movement_type = 'SALE_SHORTFALL' AND m.occurred_at >= :since
                            GROUP BY st.product_id
                        ),
                        ordered AS (
                            SELECT l.product_id, sum(l.quantity_ordered - l.quantity_received) AS qty
                            FROM purchase_order_line l JOIN purchase_order o ON o.id = l.purchase_order_id
                            WHERE o.tenant_id = :tenant AND o.status IN ('SENT', 'PARTIALLY_RECEIVED')
                            GROUP BY l.product_id
                        ),
                        season AS (
                            SELECT l.product_id,
                                   coalesce(sum(l.quantity) FILTER (WHERE s.occurred_at >= :seasonStart
                                                                      AND s.occurred_at < :seasonEnd), 0) AS window_qty,
                                   sum(l.quantity) AS year_qty
                            FROM sale_line l JOIN sale s ON s.id = l.sale_id
                            WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                              AND s.occurred_at >= :yearStart AND s.occurred_at < :yearEnd
                            GROUP BY l.product_id
                        )
                        SELECT st.product_id, p.cip13, p.designation, st.reorder_threshold, st.reorder_quantity,
                               st.coverage_days, st.safety_days, st.reserved_quantity, st.shortfall_quantity,
                               pref.supplier_id, pref.supplier_name, coalesce(pref.lead_time_days, 1) AS lead_time_days,
                               coalesce(pref.pack_size, 1) AS pack_size, pref.purchase_price_cents,
                               coalesce(sold.qty, 0) AS sold, coalesce(lost.qty, 0) AS lost, coalesce(ordered.qty, 0) AS on_order,
                               coalesce(l.sellable, 0) AS sellable, coalesce(l.expiring, 0) AS expiring,
                               coalesce(season.window_qty, 0) AS season_window, coalesce(season.year_qty, 0) AS season_year
                        FROM stock st
                        JOIN product p ON p.id = st.product_id AND p.active
                        LEFT JOIN pref ON pref.product_id = st.product_id
                        LEFT JOIN sold ON sold.product_id = st.product_id
                        LEFT JOIN lost ON lost.product_id = st.product_id
                        LEFT JOIN ordered ON ordered.product_id = st.product_id
                        LEFT JOIN season ON season.product_id = st.product_id
                        LEFT JOIN LATERAL (
                            SELECT sum(quantity) FILTER (WHERE expiry_date > :today::date) AS sellable,
                                   sum(quantity) FILTER (WHERE expiry_date > :today::date
                                        AND expiry_date <= :today::date + (coalesce(pref.lead_time_days, 1) + st.coverage_days) * interval '1 day') AS expiring
                            FROM stock_lot WHERE stock_id = st.id AND quantity > 0
                        ) l ON true
                        WHERE st.tenant_id = :tenant AND NOT st.exclude_from_ordering
                          AND (:supplier::uuid IS NULL OR pref.supplier_id = :supplier::uuid)
                        ORDER BY p.designation""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("supplier", supplierId)
                .param("since", Timestamp.from(periodStart))
                .param("today", Date.valueOf(today))
                .param("seasonStart", Timestamp.from(seasonStart))
                .param("seasonEnd", Timestamp.from(seasonEnd))
                .param("yearStart", Timestamp.from(yearStart))
                .param("yearEnd", Timestamp.from(yearEnd))
                .query((rs, rowNum) -> {
                    int available = Math.max(rs.getInt("sellable") - rs.getInt("reserved_quantity"), 0);
                    int packSize = rs.getInt("pack_size");
                    double seasonalFactor = seasonalFactor(rs.getLong("season_window"), rs.getLong("season_year"));
                    Proposal proposal = OrderProposalCalculator.propose(new Input(
                            rs.getLong("sold"), rs.getLong("lost"), VELOCITY_PERIOD_DAYS, available,
                            rs.getInt("on_order"), rs.getInt("expiring"), rs.getInt("reorder_threshold"),
                            rs.getInt("reorder_quantity"), rs.getInt("lead_time_days"), rs.getInt("coverage_days"),
                            rs.getInt("safety_days"), packSize, seasonalFactor));
                    return new ProposalLine(rs.getObject("product_id", UUID.class), rs.getString("cip13"),
                            rs.getString("designation"), rs.getObject("supplier_id", UUID.class),
                            rs.getString("supplier_name"), available, rs.getInt("on_order"), rs.getInt("expiring"),
                            rs.getLong("sold"), rs.getLong("lost"), proposal.dailyDemand(), proposal.targetStock(),
                            proposal.quantity(), packSize, (Long) rs.getObject("purchase_price_cents"),
                            proposal.reasons(), seasonalFactor);
                })
                .list()
                .stream()
                .filter(line -> line.suggestedQuantity() > 0)
                .toList();
        long total = lines.stream()
                .mapToLong(l -> l.purchasePriceCents() == null ? 0 : l.purchasePriceCents() * l.suggestedQuantity())
                .sum();
        return new ProposalSheet(today.plusDays(1), VELOCITY_PERIOD_DAYS, lines, total);
    }

    // ------------------------------------------------------------------ orders

    public record OrderLineCommand(UUID productId, int quantity, Long unitCostCents, Integer suggestedQuantity,
                                   String reason) {
    }

    /** What the pharmacy needs to reach the supplier and to check the order before sending it. */
    public record SupplierContact(String contactName, String phone, String whatsapp, String email,
                                  long minOrderCents, int leadTimeDays) {
    }

    /**
     * A purchase order with its follow-up: {@code overdue} when goods are still awaited after the
     * announced day, {@code shortClosed} when it was closed although some units never came.
     */
    public record OrderView(UUID id, String number, UUID supplierId, String supplierName, String status,
                            Instant createdAt, Instant sentAt, LocalDate expectedAt, Instant closedAt,
                            long totalCents, String notes, boolean overdue, boolean shortClosed, int orderedUnits,
                            int receivedUnits, long receivedCents, long remainingCents, SupplierContact supplier,
                            List<OrderLineView> lines, boolean quantitiesHidden) {

        /**
         * Blind receiving: the same order without what was ordered, received or remains (zeros),
         * flagged so the screen says why.
         */
        public OrderView withoutQuantities() {
            return new OrderView(id, number, supplierId, supplierName, status, createdAt, sentAt, expectedAt, closedAt,
                    0, notes, overdue, shortClosed, 0, 0, 0, 0, supplier,
                    lines.stream().map(l -> new OrderLineView(l.lineNo(), l.productId(), l.designation(), l.cip13(),
                            0, 0, l.unitCostCents(), l.packSize(), null, null, l.locationCode(), l.locationName(),
                            l.locationKind(), l.locationDetail(), l.imageVersion())).toList(), true);
        }
    }

    /**
     * What the receiving form gets: the products awaited and where to put them away; the ordered
     * and remaining quantities only when the pharmacy does not receive blind.
     */
    public record ReceivingLine(int lineNo, UUID productId, String designation, String cip13, long unitCostCents,
                                Integer quantityOrdered, Integer quantityRemaining, String locationCode,
                                String locationName, String locationKind, String locationDetail) {
    }

    public record ReceivingView(UUID orderId, String number, UUID supplierId, String supplierName, String status,
                                boolean blind, List<ReceivingLine> lines) {
    }

    /** One order line; the location says where the delivered boxes are put away (null when not assigned). */
    public record OrderLineView(int lineNo, UUID productId, String designation, String cip13, int quantityOrdered,
                                int quantityReceived, long unitCostCents, int packSize, Integer suggestedQuantity,
                                String reason, String locationCode, String locationName, String locationKind,
                                String locationDetail, String imageVersion) {
    }

    /** Key figures of the tracking screen, as of {@code today} in the pharmacy's calendar. */
    public record OrderSummary(LocalDate today, int drafts, int open, int overdue, int expectedToday,
                               long openAmountCents) {
    }

    /** A delivery booked against an order. */
    public record OrderReceiptView(UUID id, Instant receivedAt, String supplierInvoiceNumber, LocalDate invoiceDate,
                                   long totalExclVatCents, long vatCents, int lines, int units, String receivedBy) {
    }

    /** Orders written by a generation, and the proposal lines no supplier sells yet. */
    public record GenerationView(List<OrderView> orders, int created, int updated, List<ProposalLine> unassigned) {
    }

    @Transactional
    public OrderView createOrder(UUID supplierId, List<OrderLineCommand> lines, String notes) {
        var supplier = suppliers.findById(supplierId).orElseThrow(() -> new NotFoundException("Supplier", supplierId));
        var order = newDraft(supplierId, lines, notes);
        audit.record("PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", order.id(), Map.of(
                "number", order.number(), "supplier", supplier.name(), "lines", Integer.toString(order.lines().size())));
        return reload(order.id());
    }

    /**
     * Turns the current proposal into one draft per supplier in one go. A supplier that already
     * has a draft gets its lines replaced rather than a second draft, so generating twice gives
     * the same drafts. The supplier minimum is checked when sending, not here.
     */
    @Transactional
    public GenerationView generate(UUID supplierId, Map<UUID, Integer> overrides) {
        UUID tenant = TenantContext.requireTenant().value();
        // Two pharmacists generating at the same time must not each create a draft for the same supplier
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 7342))")
                .param("key", tenant + ":purchase-order-generation")
                .query((RowCallbackHandler) rs -> { });
        ProposalSheet sheet = proposal(supplierId);
        Map<UUID, ProposalLine> proposed = sheet.lines().stream()
                .collect(Collectors.toMap(ProposalLine::productId, line -> line));
        OrderGrouping.Result grouping = OrderGrouping.group(sheet.lines().stream()
                .map(line -> new OrderGrouping.Candidate(line.productId(), line.supplierId(), line.suggestedQuantity(),
                        line.purchasePriceCents(), reasonText(line.reasons())))
                .toList(), overrides);
        List<UUID> supplierIds = grouping.groups().stream().map(OrderGrouping.Group::supplierId).toList();
        Map<UUID, PurchaseOrderEntity> drafts = new HashMap<>();
        Map<UUID, String> supplierNames = new HashMap<>();
        if (!supplierIds.isEmpty()) {
            // most recent draft first: it is the one the pharmacist has been working on
            orders.findWithLinesBySupplier(PurchaseOrderEntity.Status.DRAFT, supplierIds)
                    .forEach(draft -> drafts.putIfAbsent(draft.supplierId(), draft));
            suppliers.findAllById(supplierIds).forEach(s -> supplierNames.put(s.id(), s.name()));
        }
        List<UUID> written = new ArrayList<>();
        int created = 0;
        for (OrderGrouping.Group group : grouping.groups()) {
            List<OrderLineCommand> commands = group.lines().stream()
                    .map(line -> new OrderLineCommand(line.productId(), line.quantity(), line.unitCostCents(),
                            line.suggestedQuantity(), line.reason()))
                    .toList();
            PurchaseOrderEntity draft = drafts.get(group.supplierId());
            boolean replaced = draft != null;
            if (replaced) {
                draft.replaceLines(toLines(group.supplierId(), commands));
            } else {
                draft = newDraft(group.supplierId(), commands, null);
                created++;
            }
            written.add(draft.id());
            audit.record(replaced ? "PURCHASE_ORDER_REGENERATED" : "PURCHASE_ORDER_GENERATED", "PURCHASE_ORDER",
                    draft.id(), Map.of("number", draft.number(),
                            "supplier", supplierNames.getOrDefault(group.supplierId(), "?"),
                            "lines", Integer.toString(group.lines().size()),
                            "estimatedCents", Long.toString(group.estimatedCents())));
        }
        entityManager.flush();
        return new GenerationView(views(written), created, written.size() - created,
                grouping.unassigned().stream().map(c -> proposed.get(c.productId())).toList());
    }

    @Transactional
    public OrderView updateOrder(UUID orderId, List<OrderLineCommand> lines, String notes) {
        var order = orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        order.replaceLines(toLines(order.supplierId(), lines));
        order.updateNotes(notes);
        audit.record("PURCHASE_ORDER_UPDATED", "PURCHASE_ORDER", orderId, Map.of("number", order.number(),
                "lines", Integer.toString(order.lines().size()), "totalCents", Long.toString(order.totalCents())));
        return reload(orderId);
    }

    @Transactional
    public OrderView send(UUID orderId) {
        var order = orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        var supplier = suppliers.findById(order.supplierId()).orElseThrow();
        if (order.totalCents() < supplier.minOrderCents()) {
            throw new BusinessRuleException("below-minimum", "Montant inférieur au minimum de commande du fournisseur");
        }
        order.send(clock.instant(), calendar.today().plusDays(Math.max(supplier.leadTimeDays(), 1)));
        audit.record("PURCHASE_ORDER_SENT", "PURCHASE_ORDER", orderId, Map.of("number", order.number(),
                "totalCents", Long.toString(order.totalCents())));
        return reload(orderId);
    }

    @Transactional
    public OrderView cancel(UUID orderId) {
        var order = orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        order.cancel(clock.instant());
        audit.record("PURCHASE_ORDER_CANCELLED", "PURCHASE_ORDER", orderId, Map.of("number", order.number()));
        return reload(orderId);
    }

    /** The supplier announced another delivery day for goods still awaited. */
    @Transactional
    public OrderView reschedule(UUID orderId, LocalDate expectedAt) {
        var order = orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        LocalDate previous = order.expectedAt();
        order.reschedule(expectedAt, calendar.today());
        audit.record("PURCHASE_ORDER_RESCHEDULED", "PURCHASE_ORDER", orderId, Map.of("number", order.number(),
                "from", String.valueOf(previous), "to", expectedAt.toString()));
        return reload(orderId);
    }

    /** Closes an order whose remainder will not be delivered (out of stock at the wholesaler, etc.). */
    @Transactional
    public OrderView closeShort(UUID orderId, String reason) {
        var order = orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        int givenUp = order.closeShort(clock.instant());
        audit.record("PURCHASE_ORDER_CLOSED", "PURCHASE_ORDER", orderId, Map.of("number", order.number(),
                "unitsNotDelivered", Integer.toString(givenUp), "reason", reason == null ? "" : reason));
        return reload(orderId);
    }

    @Transactional(readOnly = true)
    public OrderView order(UUID orderId) {
        return views(List.of(orderId)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Purchase order", orderId));
    }

    /** Orders of one tracking scope (or of one exact status), optionally for one supplier. */
    @Transactional(readOnly = true)
    public List<OrderView> orders(OrderTracking.Scope scope, PurchaseOrderEntity.Status status, UUID supplierId,
                                  int limit) {
        List<String> statuses = status != null ? List.of(status.name()) : scope.statuses();
        List<UUID> ids = jdbc.sql("""
                        SELECT o.id FROM purchase_order o
                        WHERE o.tenant_id = :tenant AND o.status IN (:statuses)
                          AND (:supplier::uuid IS NULL OR o.supplier_id = :supplier::uuid)
                          AND (NOT :overdueOnly OR o.expected_at < :today)
                        ORDER BY CASE WHEN :byExpected THEN o.expected_at END ASC NULLS LAST, o.created_at DESC
                        LIMIT :limit""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("statuses", statuses)
                .param("supplier", supplierId)
                .param("overdueOnly", status == null && scope == OrderTracking.Scope.OVERDUE)
                .param("today", Date.valueOf(calendar.today()))
                .param("byExpected", status == null && scope.byExpectedDate())
                .param("limit", Math.clamp(limit, 1, 200))
                .query(UUID.class)
                .list();
        return views(ids);
    }

    @Transactional(readOnly = true)
    public OrderSummary summary() {
        LocalDate today = calendar.today();
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE o.status = 'DRAFT') AS drafts,
                               count(*) FILTER (WHERE o.status <> 'DRAFT') AS open,
                               count(*) FILTER (WHERE o.status <> 'DRAFT' AND o.expected_at < :today) AS overdue,
                               count(*) FILTER (WHERE o.status <> 'DRAFT' AND o.expected_at = :today) AS expected_today,
                               coalesce(sum(r.remaining) FILTER (WHERE o.status <> 'DRAFT'), 0) AS open_amount
                        FROM purchase_order o
                        LEFT JOIN LATERAL (
                            SELECT sum(greatest(l.quantity_ordered - l.quantity_received, 0) * l.unit_cost_cents) AS remaining
                            FROM purchase_order_line l WHERE l.purchase_order_id = o.id
                        ) r ON true
                        WHERE o.tenant_id = :tenant AND o.status IN ('DRAFT', 'SENT', 'PARTIALLY_RECEIVED')""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("today", Date.valueOf(today))
                .query((rs, rowNum) -> new OrderSummary(today, rs.getInt("drafts"), rs.getInt("open"),
                        rs.getInt("overdue"), rs.getInt("expected_today"), rs.getLong("open_amount")))
                .single();
    }

    /** Deliveries booked against an order, oldest first (the order's timeline). */
    @Transactional(readOnly = true)
    public List<OrderReceiptView> orderReceipts(UUID orderId) {
        orders.findById(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        return jdbc.sql("""
                        SELECT g.id, g.received_at, g.supplier_invoice_number, g.invoice_date, g.total_excl_vat_cents,
                               g.vat_cents, u.display_name, coalesce(gl.lines, 0) AS lines, coalesce(gl.units, 0) AS units
                        FROM goods_receipt g
                        LEFT JOIN app_user u ON u.id = g.received_by
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS lines, sum(quantity) AS units
                            FROM goods_receipt_line WHERE goods_receipt_id = g.id
                        ) gl ON true
                        WHERE g.tenant_id = :tenant AND g.purchase_order_id = :order
                        ORDER BY g.received_at""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("order", orderId)
                .query((rs, rowNum) -> {
                    Date invoiceDate = rs.getDate("invoice_date");
                    return new OrderReceiptView(rs.getObject("id", UUID.class),
                            rs.getTimestamp("received_at").toInstant(), rs.getString("supplier_invoice_number"),
                            invoiceDate == null ? null : invoiceDate.toLocalDate(), rs.getLong("total_excl_vat_cents"),
                            rs.getLong("vat_cents"), rs.getInt("lines"), rs.getInt("units"),
                            rs.getString("display_name"));
                })
                .list();
    }

    /** Plain text ready to paste in WhatsApp or an e-mail to the wholesaler. */
    @Transactional(readOnly = true)
    public String orderAsText(UUID orderId, String pharmacyName) {
        OrderView order = order(orderId);
        var text = new StringBuilder();
        text.append("Commande ").append(order.number()).append(" - ").append(pharmacyName).append('\n');
        text.append("Fournisseur : ").append(order.supplierName()).append('\n');
        text.append("Date : ").append(calendar.today().format(FRENCH_DATE)).append('\n');
        if (order.expectedAt() != null) {
            text.append("Livraison souhaitée : ").append(order.expectedAt().format(FRENCH_DATE)).append('\n');
        }
        text.append('\n');
        for (OrderLineView line : order.lines()) {
            text.append(line.quantityOrdered()).append(" x ").append(line.designation())
                    .append(" (CIP ").append(line.cip13()).append(")\n");
        }
        text.append('\n').append(order.lines().size()).append(" références, ")
                .append(order.lines().stream().mapToInt(OrderLineView::quantityOrdered).sum()).append(" unités");
        return text.toString();
    }

    // ------------------------------------------------------------------ deliveries

    public record ReceiptLineCommand(UUID productId, String lotNumber, LocalDate expiryDate, int quantity,
                                     long unitCostCents, BigDecimal vatRate, Long sellingPriceCents) {
    }

    public record ReceiptCommand(UUID supplierId, UUID purchaseOrderId, String supplierInvoiceNumber,
                                 LocalDate invoiceDate, String notes, List<ReceiptLineCommand> lines) {
    }

    /** @param check comparison with the order (null for a delivery without order) */
    public record ReceiptView(UUID id, long totalExclVatCents, long vatCents, int lines, String orderStatus,
                              ReceivingControlService.ReceiptCheck check) {
    }

    /** The receiving form of an order: quantities left out in blind mode. */
    @Transactional(readOnly = true)
    public ReceivingView receiving(UUID orderId) {
        OrderView order = order(orderId);
        boolean blind = receivingControl.blindReceiving();
        List<ReceivingLine> lines = order.lines().stream()
                .filter(l -> !blind || l.quantityReceived() < l.quantityOrdered())
                .map(l -> new ReceivingLine(l.lineNo(), l.productId(), l.designation(), l.cip13(), l.unitCostCents(),
                        blind ? null : l.quantityOrdered(),
                        blind ? null : Math.max(l.quantityOrdered() - l.quantityReceived(), 0),
                        l.locationCode(), l.locationName(), l.locationKind(), l.locationDetail()))
                .toList();
        return new ReceivingView(order.id(), order.number(), order.supplierId(), order.supplierName(), order.status(),
                blind, lines);
    }

    /**
     * Delivery note / supplier invoice: lots enter the stock (FEFO), prices are updated, the
     * order progresses and the purchase is booked, all in one transaction.
     */
    @Transactional
    public ReceiptView receive(ReceiptCommand command) {
        var supplier = suppliers.findById(command.supplierId())
                .orElseThrow(() -> new NotFoundException("Supplier", command.supplierId()));
        if (command.lines().isEmpty()) {
            throw new BusinessRuleException("empty-receipt", "La réception ne contient aucune ligne");
        }
        PurchaseOrderEntity order = command.purchaseOrderId() == null ? null
                : orders.findWithLines(command.purchaseOrderId())
                        .orElseThrow(() -> new NotFoundException("Purchase order", command.purchaseOrderId()));
        if (order != null && !order.supplierId().equals(command.supplierId())) {
            throw new BusinessRuleException("supplier-mismatch", "La commande n'est pas de ce fournisseur");
        }
        var user = TenantContext.requireTenantUser();
        boolean separationWarning = order != null && receivingControl.checkReceiver(order.id(), order.createdBy());
        // What the order still awaited before this delivery, compared once the receipt is booked
        Map<UUID, ReceivingControlService.Expected> expected = new LinkedHashMap<>();
        Map<UUID, String> designations = designations(command.lines().stream()
                .map(ReceiptLineCommand::productId).toList(), order);
        if (order != null) {
            for (PurchaseOrderLineEntity line : order.lines()) {
                expected.merge(line.productId(), new ReceivingControlService.Expected(
                                designations.getOrDefault(line.productId(), ""),
                                Math.max(line.quantityOrdered() - line.quantityReceived(), 0)),
                        (a, b) -> new ReceivingControlService.Expected(a.designation(), a.remaining() + b.remaining()));
            }
        }
        Instant now = clock.instant();
        UUID receiptId = UUID.randomUUID();
        long totalExclVat = 0;
        long totalVat = 0;
        var inserted = new ArrayList<Object[]>();
        for (ReceiptLineCommand line : command.lines()) {
            UUID lotId = stock.receiveLot(line.productId(), line.lotNumber(), line.expiryDate(), line.quantity(),
                    line.unitCostCents());
            if (line.sellingPriceCents() != null) {
                stock.updateSellingPrice(line.productId(), line.sellingPriceCents());
            }
            supplierProducts.findBySupplierIdAndProductId(command.supplierId(), line.productId())
                    .ifPresentOrElse(link -> link.recordPurchase(line.unitCostCents(), now),
                            () -> supplierProducts.save(new SupplierProductEntity(command.supplierId(),
                                    line.productId(), null, line.unitCostCents(), 1, false)));
            if (order != null) {
                order.receive(line.productId(), line.quantity(), now);
            }
            long exclVat = line.unitCostCents() * line.quantity();
            long vat = VatSplit.ofExclusive(exclVat, line.vatRate()).vatCents();
            totalExclVat += exclVat;
            totalVat += vat;
            inserted.add(new Object[] {lotId, line});
        }
        // The lots and order lines live in the persistence context: make them visible to the SQL below
        entityManager.flush();
        jdbc.sql("""
                        INSERT INTO goods_receipt (id, tenant_id, supplier_id, purchase_order_id, supplier_invoice_number,
                                                   invoice_date, received_by, received_at, total_excl_vat_cents, vat_cents, notes)
                        VALUES (:id, :tenant, :supplier, :order, :invoice, :invoiceDate, :by, :at, :total, :vat, :notes)""")
                .param("id", receiptId).param("tenant", user.tenant().value()).param("supplier", command.supplierId())
                .param("order", command.purchaseOrderId()).param("invoice", command.supplierInvoiceNumber())
                .param("invoiceDate", command.invoiceDate() == null ? null : Date.valueOf(command.invoiceDate()))
                .param("by", user.userId()).param("at", Timestamp.from(now)).param("total", totalExclVat)
                .param("vat", totalVat).param("notes", command.notes()).update();
        for (Object[] row : inserted) {
            ReceiptLineCommand line = (ReceiptLineCommand) row[1];
            jdbc.sql("""
                            INSERT INTO goods_receipt_line (id, tenant_id, goods_receipt_id, product_id, lot_id, lot_number,
                                                            expiry_date, quantity, unit_cost_cents, vat_rate, selling_price_cents)
                            VALUES (:id, :tenant, :receipt, :product, :lot, :lotNumber, :expiry, :qty, :cost, :vat, :price)""")
                    .param("id", UUID.randomUUID()).param("tenant", user.tenant().value()).param("receipt", receiptId)
                    .param("product", line.productId()).param("lot", row[0]).param("lotNumber", line.lotNumber())
                    .param("expiry", Date.valueOf(line.expiryDate())).param("qty", line.quantity())
                    .param("cost", line.unitCostCents()).param("vat", line.vatRate() == null ? BigDecimal.ZERO : line.vatRate())
                    .param("price", line.sellingPriceCents()).update();
        }
        accounting.post(Postings.goodsReceipt(totalExclVat, totalVat, supplier.name(), command.supplierInvoiceNumber(),
                command.invoiceDate() == null ? calendar.today() : command.invoiceDate()), "GOODS_RECEIPT", receiptId);
        audit.record("GOODS_RECEIVED", "GOODS_RECEIPT", receiptId, Map.of(
                "supplier", supplier.name(), "invoice", String.valueOf(command.supplierInvoiceNumber()),
                "lines", Integer.toString(command.lines().size()), "totalExclVatCents", Long.toString(totalExclVat)));
        ReceivingControlService.ReceiptCheck check = null;
        if (order != null) {
            Map<UUID, Integer> received = new LinkedHashMap<>();
            command.lines().forEach(line -> received.merge(line.productId(), line.quantity(), Integer::sum));
            check = receivingControl.record(receiptId, order.id(), order.number(), expected, received, designations,
                    separationWarning);
        }
        return new ReceiptView(receiptId, totalExclVat, totalVat, command.lines().size(),
                order == null ? null : order.status().name(), check);
    }

    private Map<UUID, String> designations(List<UUID> delivered, PurchaseOrderEntity order) {
        Set<UUID> ids = new LinkedHashSet<>(delivered);
        if (order != null) {
            order.lines().forEach(line -> ids.add(line.productId()));
        }
        Map<UUID, String> names = new HashMap<>();
        jdbc.sql("SELECT id, designation FROM product WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new))
                .query((RowCallbackHandler) rs -> names.put(rs.getObject("id", UUID.class), rs.getString("designation")));
        return names;
    }

    // ------------------------------------------------------------------ helpers

    private List<PurchaseOrderLineEntity> toLines(UUID supplierId, List<OrderLineCommand> commands) {
        Map<UUID, SupplierProductEntity> catalog = supplierProducts.findBySupplierId(supplierId).stream()
                .collect(Collectors.toMap(SupplierProductEntity::productId, link -> link));
        List<PurchaseOrderLineEntity> lines = new ArrayList<>();
        short lineNo = 1;
        for (OrderLineCommand command : commands) {
            if (command.quantity() <= 0) {
                continue;
            }
            var link = catalog.get(command.productId());
            long cost = command.unitCostCents() != null ? command.unitCostCents()
                    : link != null && link.purchasePriceCents() != null ? link.purchasePriceCents() : 0L;
            lines.add(new PurchaseOrderLineEntity(lineNo++, command.productId(), command.quantity(), cost,
                    link == null ? 1 : link.packSize(), command.suggestedQuantity(), command.reason()));
        }
        if (lines.isEmpty()) {
            throw new BusinessRuleException("empty-order", "La commande ne contient aucune ligne");
        }
        return lines;
    }

    private PurchaseOrderEntity newDraft(UUID supplierId, List<OrderLineCommand> lines, String notes) {
        var user = TenantContext.requireTenantUser();
        String number = "CMD-" + calendar.today().getYear() + "-" + String.format("%05d", sequences.next(ORDER_SEQUENCE));
        var order = new PurchaseOrderEntity(supplierId, number, user.userId(), clock.instant(), notes);
        order.replaceLines(toLines(supplierId, lines));
        return orders.save(order);
    }

    private static String reasonText(List<OrderProposalCalculator.Reason> reasons) {
        return reasons.stream().map(reason -> switch (reason) {
            case BELOW_TARGET -> "sous la couverture";
            case BELOW_THRESHOLD -> "sous le seuil";
            case EXPIRING_REPLACEMENT -> "péremption proche";
            case LOST_SALES -> "ventes perdues";
            case SEASONAL_UPTURN -> "saison haute";
        }).collect(Collectors.joining(", "));
    }

    /** View of an order just written in this transaction. */
    private OrderView reload(UUID orderId) {
        entityManager.flush();
        return views(List.of(orderId)).getFirst();
    }

    /** Orders with supplier and lines in two queries, in the order of {@code ids}. */
    private List<OrderView> views(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        UUID tenant = TenantContext.requireTenant().value();
        UUID[] idArray = ids.toArray(UUID[]::new);
        Map<UUID, List<OrderLineView>> lines = new HashMap<>();
        jdbc.sql("""
                        SELECT l.purchase_order_id, l.line_no, l.product_id, p.designation, p.cip13, l.quantity_ordered,
                               l.quantity_received, l.unit_cost_cents, l.pack_size, l.suggested_quantity, l.reason,
                               sl.code AS location_code, sl.name AS location_name, sl.kind AS location_kind,
                               CASE WHEN sl.id IS NOT NULL THEN st.location_detail END AS location_detail,
                               pi.sha256 AS image_sha256
                        FROM purchase_order_line l JOIN product p ON p.id = l.product_id
                        LEFT JOIN stock st ON st.tenant_id = l.tenant_id AND st.product_id = l.product_id
                        LEFT JOIN storage_location sl ON sl.id = st.location_id
                        LEFT JOIN product_image pi ON pi.tenant_id = l.tenant_id AND pi.product_id = l.product_id
                        WHERE l.tenant_id = :tenant AND l.purchase_order_id = ANY(:ids)
                        ORDER BY l.purchase_order_id, l.line_no""")
                .param("tenant", tenant)
                .param("ids", idArray)
                .query((RowCallbackHandler) rs -> lines
                        .computeIfAbsent(rs.getObject("purchase_order_id", UUID.class), id -> new ArrayList<>())
                        .add(new OrderLineView(rs.getInt("line_no"), rs.getObject("product_id", UUID.class),
                                rs.getString("designation"), rs.getString("cip13"), rs.getInt("quantity_ordered"),
                                rs.getInt("quantity_received"), rs.getLong("unit_cost_cents"), rs.getInt("pack_size"),
                                (Integer) rs.getObject("suggested_quantity"), rs.getString("reason"),
                                rs.getString("location_code"), rs.getString("location_name"),
                                rs.getString("location_kind"), rs.getString("location_detail"),
                                ProductImageRules.version(rs.getString("image_sha256")))));
        LocalDate today = calendar.today();
        Map<UUID, OrderView> byId = new HashMap<>();
        jdbc.sql("""
                        SELECT o.id, o.number, o.supplier_id, s.name, s.contact_name, s.phone, s.whatsapp, s.email,
                               s.min_order_cents, s.lead_time_days, o.status, o.created_at, o.sent_at, o.expected_at,
                               o.closed_at, o.total_cents, o.notes
                        FROM purchase_order o JOIN supplier s ON s.id = o.supplier_id
                        WHERE o.tenant_id = :tenant AND o.id = ANY(:ids)""")
                .param("tenant", tenant)
                .param("ids", idArray)
                .query((RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String status = rs.getString("status");
                    Date expected = rs.getDate("expected_at");
                    LocalDate expectedAt = expected == null ? null : expected.toLocalDate();
                    List<OrderLineView> orderLines = List.copyOf(lines.getOrDefault(id, List.of()));
                    int ordered = orderLines.stream().mapToInt(OrderLineView::quantityOrdered).sum();
                    int received = orderLines.stream().mapToInt(OrderLineView::quantityReceived).sum();
                    long receivedCents = orderLines.stream()
                            .mapToLong(l -> (long) l.quantityReceived() * l.unitCostCents()).sum();
                    long remainingCents = !OrderTracking.open(status) ? 0 : orderLines.stream()
                            .mapToLong(l -> (long) Math.max(l.quantityOrdered() - l.quantityReceived(), 0)
                                    * l.unitCostCents())
                            .sum();
                    boolean incomplete = orderLines.stream().anyMatch(l -> l.quantityReceived() < l.quantityOrdered());
                    byId.put(id, new OrderView(id, rs.getString("number"), rs.getObject("supplier_id", UUID.class),
                            rs.getString("name"), status, instant(rs.getTimestamp("created_at")),
                            instant(rs.getTimestamp("sent_at")), expectedAt, instant(rs.getTimestamp("closed_at")),
                            rs.getLong("total_cents"), rs.getString("notes"),
                            OrderTracking.overdue(status, expectedAt, today),
                            OrderTracking.shortClosed(status, incomplete), ordered, received, receivedCents,
                            remainingCents, new SupplierContact(rs.getString("contact_name"), rs.getString("phone"),
                                    rs.getString("whatsapp"), rs.getString("email"), rs.getLong("min_order_cents"),
                                    rs.getInt("lead_time_days")),
                            orderLines, false));
                });
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
