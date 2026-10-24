package com.pharmacore.procurement.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.accounting.domain.VatSplit;
import com.pharmacore.inventory.application.StockService;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.procurement.domain.OrderProposalCalculator;
import com.pharmacore.procurement.domain.OrderProposalCalculator.Input;
import com.pharmacore.procurement.domain.OrderProposalCalculator.Proposal;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suppliers, order proposals ("fiche de commande du lendemain"), purchase orders and deliveries. */
@Service
public class ProcurementService {

    public static final int VELOCITY_PERIOD_DAYS = 28;
    static final String ORDER_SEQUENCE = "PURCHASE_ORDER";
    static final String SUPPLIER_SEQUENCE = "SUPPLIER";

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
    private final Clock clock;

    public ProcurementService(SupplierRepository suppliers, SupplierProductRepository supplierProducts,
                              PurchaseOrderRepository orders,
                              StockService stock, AccountingPoster accounting, TenantSequenceRepository sequences,
                              TenantCalendar calendar, AuditTrail audit, JdbcClient jdbc, EntityManager entityManager,
                              Clock clock) {
        this.entityManager = entityManager;
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

    public record ProposalLine(UUID productId, String cip13, String designation, UUID supplierId, String supplierName,
                               int available, int onOrder, int expiringSoon, long soldLastPeriod, long shortfall,
                               double dailyDemand, int targetStock, int suggestedQuantity, int packSize,
                               Long purchasePriceCents, List<OrderProposalCalculator.Reason> reasons) {
    }

    public record ProposalSheet(LocalDate date, int periodDays, List<ProposalLine> lines, long estimatedTotalCents) {
    }

    /**
     * Tomorrow's order sheet. One line per product needing replenishment, routed to its
     * preferred supplier (or left unassigned). {@code supplierId} restricts to one supplier.
     */
    @Transactional(readOnly = true)
    public ProposalSheet proposal(UUID supplierId) {
        LocalDate today = calendar.today();
        Instant periodStart = today.minusDays(VELOCITY_PERIOD_DAYS).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<ProposalLine> lines = jdbc.sql("""
                        WITH pref AS (
                            SELECT DISTINCT ON (sp.product_id) sp.product_id, sp.supplier_id, s.name AS supplier_name,
                                   s.lead_time_days, sp.pack_size, sp.purchase_price_cents
                            FROM supplier_product sp JOIN supplier s ON s.id = sp.supplier_id AND s.active
                            WHERE (:supplier::uuid IS NULL OR sp.supplier_id = :supplier::uuid)
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
                        )
                        SELECT st.product_id, p.cip13, p.designation, st.reorder_threshold, st.reorder_quantity,
                               st.coverage_days, st.safety_days, st.reserved_quantity, st.shortfall_quantity,
                               pref.supplier_id, pref.supplier_name, coalesce(pref.lead_time_days, 1) AS lead_time_days,
                               coalesce(pref.pack_size, 1) AS pack_size, pref.purchase_price_cents,
                               coalesce(sold.qty, 0) AS sold, coalesce(lost.qty, 0) AS lost, coalesce(ordered.qty, 0) AS on_order,
                               coalesce(l.sellable, 0) AS sellable, coalesce(l.expiring, 0) AS expiring
                        FROM stock st
                        JOIN product p ON p.id = st.product_id AND p.active
                        LEFT JOIN pref ON pref.product_id = st.product_id
                        LEFT JOIN sold ON sold.product_id = st.product_id
                        LEFT JOIN lost ON lost.product_id = st.product_id
                        LEFT JOIN ordered ON ordered.product_id = st.product_id
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
                .query((rs, rowNum) -> {
                    int available = Math.max(rs.getInt("sellable") - rs.getInt("reserved_quantity"), 0);
                    int packSize = rs.getInt("pack_size");
                    Proposal proposal = OrderProposalCalculator.propose(new Input(
                            rs.getLong("sold"), rs.getLong("lost"), VELOCITY_PERIOD_DAYS, available,
                            rs.getInt("on_order"), rs.getInt("expiring"), rs.getInt("reorder_threshold"),
                            rs.getInt("reorder_quantity"), rs.getInt("lead_time_days"), rs.getInt("coverage_days"),
                            rs.getInt("safety_days"), packSize));
                    return new ProposalLine(rs.getObject("product_id", UUID.class), rs.getString("cip13"),
                            rs.getString("designation"), rs.getObject("supplier_id", UUID.class),
                            rs.getString("supplier_name"), available, rs.getInt("on_order"), rs.getInt("expiring"),
                            rs.getLong("sold"), rs.getLong("lost"), proposal.dailyDemand(), proposal.targetStock(),
                            proposal.quantity(), packSize, (Long) rs.getObject("purchase_price_cents"),
                            proposal.reasons());
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

    public record OrderView(UUID id, String number, UUID supplierId, String supplierName, String status,
                            Instant createdAt, Instant sentAt, LocalDate expectedAt, long totalCents, String notes,
                            List<OrderLineView> lines) {
    }

    public record OrderLineView(int lineNo, UUID productId, String designation, String cip13, int quantityOrdered,
                                int quantityReceived, long unitCostCents, int packSize, Integer suggestedQuantity,
                                String reason) {
    }

    @Transactional
    public OrderView createOrder(UUID supplierId, List<OrderLineCommand> lines, String notes) {
        var supplier = suppliers.findById(supplierId).orElseThrow(() -> new NotFoundException("Supplier", supplierId));
        var user = TenantContext.requireTenantUser();
        String number = "CMD-" + calendar.today().getYear() + "-" + String.format("%05d", sequences.next(ORDER_SEQUENCE));
        var order = new PurchaseOrderEntity(supplierId, number, user.userId(), clock.instant(), notes);
        order.replaceLines(toLines(supplierId, lines));
        orders.save(order);
        audit.record("PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", order.id(), Map.of(
                "number", number, "supplier", supplier.name(), "lines", Integer.toString(lines.size())));
        return view(order);
    }

    @Transactional
    public OrderView updateOrder(UUID orderId, List<OrderLineCommand> lines, String notes) {
        var order = orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        order.replaceLines(toLines(order.supplierId(), lines));
        return view(order);
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
        return view(order);
    }

    @Transactional
    public OrderView cancel(UUID orderId) {
        var order = orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId));
        order.cancel(clock.instant());
        audit.record("PURCHASE_ORDER_CANCELLED", "PURCHASE_ORDER", orderId, Map.of("number", order.number()));
        return view(order);
    }

    @Transactional(readOnly = true)
    public OrderView order(UUID orderId) {
        return view(orders.findWithLines(orderId).orElseThrow(() -> new NotFoundException("Purchase order", orderId)));
    }

    @Transactional(readOnly = true)
    public List<OrderView> orders(PurchaseOrderEntity.Status status, int limit) {
        return orders.findRecent(status, PageRequest.of(0, Math.clamp(limit, 1, 200))).stream().map(this::view).toList();
    }

    /** Plain text ready to paste in WhatsApp or an e-mail to the wholesaler. */
    @Transactional(readOnly = true)
    public String orderAsText(UUID orderId, String pharmacyName) {
        OrderView order = order(orderId);
        var text = new StringBuilder();
        text.append("Commande ").append(order.number()).append(" - ").append(pharmacyName).append('\n');
        text.append("Fournisseur : ").append(order.supplierName()).append('\n');
        text.append("Date : ").append(calendar.today()).append("\n\n");
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

    public record ReceiptView(UUID id, long totalExclVatCents, long vatCents, int lines, String orderStatus) {
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
        return new ReceiptView(receiptId, totalExclVat, totalVat, command.lines().size(),
                order == null ? null : order.status().name());
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

    private OrderView view(PurchaseOrderEntity order) {
        String supplierName = suppliers.findById(order.supplierId()).map(SupplierEntity::name).orElse("?");
        Map<UUID, String[]> products = jdbc.sql("SELECT id, designation, cip13 FROM product WHERE id = ANY(:ids)")
                .param("ids", order.lines().stream().map(PurchaseOrderLineEntity::productId).toArray(UUID[]::new))
                .query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class),
                        new String[] {rs.getString("designation"), rs.getString("cip13")}))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new OrderView(order.id(), order.number(), order.supplierId(), supplierName, order.status().name(),
                order.createdAt(), order.sentAt(), order.expectedAt(), order.totalCents(), order.notes(),
                order.lines().stream().map(l -> new OrderLineView(l.lineNo(), l.productId(),
                        products.getOrDefault(l.productId(), new String[] {"?", ""})[0],
                        products.getOrDefault(l.productId(), new String[] {"?", ""})[1],
                        l.quantityOrdered(), l.quantityReceived(), l.unitCostCents(), l.packSize(),
                        l.suggestedQuantity(), l.reason())).toList());
    }
}
