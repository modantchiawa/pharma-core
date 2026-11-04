package com.pharmacore.customerorder.application;

import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.customerorder.domain.CustomerOrderAllocation;
import com.pharmacore.customerorder.domain.CustomerOrderKind;
import com.pharmacore.customerorder.domain.CustomerOrderStatus;
import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.followup.application.CustomerMessageService;
import com.pharmacore.followup.application.FollowUpSettingsService;
import com.pharmacore.followup.domain.MessageKind;
import com.pharmacore.inventory.application.StockLedger;
import com.pharmacore.inventory.application.StockReceived;
import com.pharmacore.inventory.domain.AllocationPlan;
import com.pharmacore.inventory.domain.LotAllocation;
import com.pharmacore.inventory.domain.ShortfallPolicy;
import com.pharmacore.inventory.persistence.StockMovementEntity;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOrigin;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleLineEntity;
import com.pharmacore.sales.persistence.TenantSequenceRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the pharmacy owes its customers: bons (a product sold and paid while out of stock, handed
 * over when it arrives) and special orders (a product ordered for a customer, paid when
 * collected). A delivery from the wholesaler reserves the units for the oldest orders it can
 * serve in full and tells the customer; the reserved units are no longer sold to anyone else.
 */
@Service
public class CustomerOrderService {

    static final String SEQUENCE = "CUSTOMER_ORDER";
    private static final String REFERENCE_TYPE = "CUSTOMER_ORDER";

    public record OrderView(UUID id, long number, CustomerOrderKind kind, CustomerOrderStatus status,
                            UUID customerId, String customerName, String holderName, String holderPhone,
                            UUID productId, String designation, String cip13, int quantity, Long unitPriceCents,
                            UUID saleId, String saleTicket, UUID deliveredSaleId, UUID purchaseOrderId,
                            String purchaseOrderNumber, String notes, String createdBy, Instant createdAt,
                            Instant readyAt, Instant deliveredAt, Instant cancelledAt, String cancelReason) {

        /** Who to call: the customer file, else the name and phone written on the bon. */
        public String contactName() {
            return customerName != null ? customerName : holderName;
        }
    }

    public record SpecialOrderCommand(UUID customerId, String holderName, String holderPhone, UUID productId,
                                      int quantity, Long unitPriceCents, String notes) {
    }

    public record Summary(int open, int ready, int vouchersOpen, int specialOpen) {
    }

    private final JdbcClient jdbc;
    private final StockLedger ledger;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final TenantSequenceRepository sequences;
    private final CustomerMessageService messages;
    private final FollowUpSettingsService followUp;
    private final AuditTrail audit;
    private final Clock clock;

    public CustomerOrderService(JdbcClient jdbc, StockLedger ledger, ProductRepository products,
                                CustomerRepository customers, TenantSequenceRepository sequences,
                                CustomerMessageService messages, FollowUpSettingsService followUp, AuditTrail audit,
                                Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.products = products;
        this.customers = customers;
        this.sequences = sequences;
        this.messages = messages;
        this.followUp = followUp;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ creation

    /** A special order taken at the counter; reserved at once when the product happens to be on the shelf. */
    @Transactional
    public OrderView createSpecial(SpecialOrderCommand command) {
        var product = products.findById(command.productId())
                .orElseThrow(() -> new NotFoundException("Product", command.productId()));
        if (command.quantity() <= 0 || command.quantity() > 9999) {
            throw new BusinessRuleException("order-quantity", "Quantité entre 1 et 9999");
        }
        String phone = blankToNull(command.holderPhone());
        if (command.customerId() == null && phone == null) {
            throw new BusinessRuleException("order-contact",
                    "Choisissez le client ou notez son téléphone pour le prévenir à l'arrivée");
        }
        if (command.customerId() != null) {
            customers.findById(command.customerId())
                    .orElseThrow(() -> new NotFoundException("Customer", command.customerId()));
        }
        var user = TenantContext.requireTenantUser();
        // the stock row is created (a product never stocked) and locked before the order exists
        ledger.lock(List.of(product.id()));
        UUID id = UUID.randomUUID();
        long number = sequences.next(SEQUENCE);
        insert(id, number, CustomerOrderKind.SPECIAL, command.customerId(), blankToNull(command.holderName()), phone,
                product.id(), command.quantity(), command.unitPriceCents(), null, null, blankToNull(command.notes()),
                user.userId());
        audit.record("CUSTOMER_ORDER_CREATED", "CUSTOMER_ORDER", id, Map.of(
                "kind", CustomerOrderKind.SPECIAL.name(), "number", Long.toString(number),
                "product", product.designation(), "quantity", Integer.toString(command.quantity())));
        serveWaiting(product.id());
        return get(id);
    }

    /**
     * The bons of a sale just recorded: one per line leaving units owed. Called by the sale, in
     * its transaction, once the sale and its lines are stored.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> recordVouchers(SaleEntity sale, SaleDraft draft) {
        if (!draft.leavesVouchers()) {
            return List.of();
        }
        var holder = draft.voucherHolder();
        List<Long> numbers = new ArrayList<>();
        short lineNo = 1;
        for (SaleDraft.Line line : draft.lines()) {
            short current = lineNo++;
            if (line.deferredQuantity() == 0) {
                continue;
            }
            SaleLineEntity saleLine = sale.lines().stream().filter(l -> l.lineNo() == current).findFirst()
                    .orElseThrow();
            UUID id = UUID.randomUUID();
            long number = sequences.next(SEQUENCE);
            insert(id, number, CustomerOrderKind.VOUCHER, draft.customerId(), holder == null ? null : holder.name(),
                    holder == null ? null : holder.phone(), line.productId(), line.deferredQuantity(),
                    line.unitPriceCents(), sale.id(), saleLine.id(), null, sale.cashierUserId());
            audit.record("CUSTOMER_ORDER_CREATED", "CUSTOMER_ORDER", id, Map.of(
                    "kind", CustomerOrderKind.VOUCHER.name(), "number", Long.toString(number),
                    "ticket", sale.ticketNumber(), "quantity", Integer.toString(line.deferredQuantity())));
            numbers.add(number);
        }
        return numbers;
    }

    // ------------------------------------------------------------------ collecting at the counter

    /**
     * Before a sale takes its units from the shelf: the special orders it collects give back
     * their reservation, so the units they held become the sale's. Live, an order that cannot
     * be collected (another product, already handed over, cancelled) refuses the sale; replayed
     * from an offline terminal, the sale is kept and the order simply stays as it was.
     *
     * @return the orders the sale collects
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> releaseForSale(SaleDraft draft, UUID sellerId) {
        Set<UUID> collected = new LinkedHashSet<>();
        for (SaleDraft.Line line : draft.lines()) {
            if (line.customerOrderId() == null) {
                continue;
            }
            Optional<OrderView> order = find(line.customerOrderId());
            // collectRefusal gives null when the order can be collected: no Optional.map here
            String refusal = order.isEmpty() ? "Commande client introuvable" : collectRefusal(order.get(), line);
            if (refusal != null) {
                if (draft.origin() == SaleOrigin.ONLINE) {
                    throw new BusinessRuleException("customer-order-not-collectable", refusal);
                }
                continue;
            }
            OrderView found = order.get();
            if (found.status() == CustomerOrderStatus.READY) {
                ledger.release(found.productId(), found.quantity(), reference(found.id(), sellerId));
            }
            collected.add(found.id());
        }
        return collected;
    }

    /** The sale is stored: the special orders it collected are handed over. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCollected(Set<UUID> orderIds, SaleEntity sale) {
        Instant now = clock.instant();
        for (UUID id : orderIds) {
            jdbc.sql("""
                            UPDATE customer_order SET status = 'DELIVERED', delivered_sale_id = :sale, delivered_at = :at,
                                                      delivered_by = :by, version = version + 1
                            WHERE id = :id AND status IN ('OPEN', 'READY')""")
                    .param("id", id).param("sale", sale.id()).param("at", Timestamp.from(now))
                    .param("by", sale.cashierUserId())
                    .update();
            audit.record("CUSTOMER_ORDER_DELIVERED", "CUSTOMER_ORDER", id, Map.of("ticket", sale.ticketNumber()));
        }
    }

    // ------------------------------------------------------------------ bons

    /**
     * Hands over the units of a bon: they leave the stock now (FEFO) and are recorded on the
     * ticket's line, so a later return puts them back into the right lots.
     */
    @Transactional
    public OrderView deliverVoucher(UUID id) {
        OrderView order = find(id).orElseThrow(() -> new NotFoundException("Customer order", id));
        if (order.kind() != CustomerOrderKind.VOUCHER) {
            throw new BusinessRuleException("not-a-voucher",
                    "Une commande spéciale se remet en caisse : ajoutez-la au panier du client");
        }
        if (!order.status().pending()) {
            throw new BusinessRuleException("order-closed", "Ce bon est déjà soldé");
        }
        var user = TenantContext.requireTenantUser();
        var reference = reference(id, user.userId());
        if (order.status() == CustomerOrderStatus.READY) {
            ledger.release(order.productId(), order.quantity(), reference);
        }
        AllocationPlan plan = ledger.withdraw(Map.of(order.productId(), order.quantity()), ShortfallPolicy.REJECT,
                StockMovementEntity.Type.SALE, reference).get(order.productId());
        UUID saleLine = jdbc.sql("SELECT sale_line_id FROM customer_order WHERE id = :id")
                .param("id", id).query(UUID.class).optional().orElse(null);
        if (saleLine != null) {
            for (LotAllocation allocation : plan.allocations()) {
                jdbc.sql("""
                                INSERT INTO sale_line_allocation (id, tenant_id, sale_line_id, lot_id, quantity)
                                VALUES (:id, :tenant, :line, :lot, :quantity)""")
                        .param("id", UUID.randomUUID()).param("tenant", user.tenant().value())
                        .param("line", saleLine).param("lot", allocation.lotId())
                        .param("quantity", allocation.quantity())
                        .update();
            }
        }
        jdbc.sql("""
                        UPDATE customer_order SET status = 'DELIVERED', delivered_at = :at, delivered_by = :by,
                                                  version = version + 1
                        WHERE id = :id""")
                .param("id", id).param("at", Timestamp.from(clock.instant())).param("by", user.userId())
                .update();
        audit.record("CUSTOMER_ORDER_DELIVERED", "CUSTOMER_ORDER", id, Map.of(
                "kind", order.kind().name(), "ticket", String.valueOf(order.saleTicket()),
                "quantity", Integer.toString(order.quantity())));
        return get(id);
    }

    /** Units still owed on a ticket's line (bons not handed over): a return or a cancellation refunds them first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int owedOnLine(UUID saleLineId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(quantity), 0) FROM customer_order
                        WHERE sale_line_id = :line AND kind = 'VOUCHER' AND status IN ('OPEN', 'READY')""")
                .param("line", saleLineId).query(Integer.class).single();
    }

    /**
     * A return refunds units of a line: the owed units go first (the customer gives up waiting).
     *
     * @return how many of the returned units were owed (they never left the shelf)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int refundOwed(UUID saleLineId, int returned, String reason) {
        int left = returned;
        int refunded = 0;
        List<OrderView> owed = jdbc.sql(SELECT + """
                         WHERE o.sale_line_id = :line AND o.kind = 'VOUCHER' AND o.status IN ('OPEN', 'READY')
                         ORDER BY o.created_at DESC""")
                .param("line", saleLineId).query(this::map).list();
        var user = TenantContext.requireTenantUser();
        for (OrderView order : owed) {
            if (left == 0) {
                break;
            }
            int take = Math.min(left, order.quantity());
            if (order.status() == CustomerOrderStatus.READY) {
                ledger.release(order.productId(), take, reference(order.id(), user.userId()));
            }
            if (take == order.quantity()) {
                close(order.id(), reason);
            } else {
                jdbc.sql("UPDATE customer_order SET quantity = quantity - :take, version = version + 1 WHERE id = :id")
                        .param("take", take).param("id", order.id()).update();
            }
            audit.record("CUSTOMER_ORDER_REFUNDED", "CUSTOMER_ORDER", order.id(), Map.of(
                    "quantity", Integer.toString(take), "reason", reason));
            left -= take;
            refunded += take;
        }
        return refunded;
    }

    /** The ticket is cancelled: its bons go with it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSaleCancelled(UUID saleId, String reason) {
        List<OrderView> owed = jdbc.sql(SELECT + """
                         WHERE o.sale_id = :sale AND o.kind = 'VOUCHER' AND o.status IN ('OPEN', 'READY')""")
                .param("sale", saleId).query(this::map).list();
        var user = TenantContext.requireTenantUser();
        for (OrderView order : owed) {
            if (order.status() == CustomerOrderStatus.READY) {
                ledger.release(order.productId(), order.quantity(), reference(order.id(), user.userId()));
            }
            close(order.id(), "Ticket annulé : " + reason);
            audit.record("CUSTOMER_ORDER_CANCELLED", "CUSTOMER_ORDER", order.id(), Map.of("reason", reason));
        }
    }

    // ------------------------------------------------------------------ cancellation, ordering

    /** A special order the customer gives up. A bon is refunded by a return of its ticket instead. */
    @Transactional
    public OrderView cancel(UUID id, String reason) {
        OrderView order = find(id).orElseThrow(() -> new NotFoundException("Customer order", id));
        if (order.kind() == CustomerOrderKind.VOUCHER) {
            throw new BusinessRuleException("voucher-refund",
                    "Un bon a été payé : remboursez le client par un retour du ticket " + order.saleTicket()
                            + ", le bon est soldé avec lui");
        }
        if (!order.status().pending()) {
            throw new BusinessRuleException("order-closed", "Cette commande est déjà soldée");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("reason-required", "Le motif est obligatoire");
        }
        var user = TenantContext.requireTenantUser();
        if (order.status() == CustomerOrderStatus.READY) {
            ledger.release(order.productId(), order.quantity(), reference(id, user.userId()));
        }
        close(id, reason.trim());
        audit.record("CUSTOMER_ORDER_CANCELLED", "CUSTOMER_ORDER", id, Map.of("reason", reason.trim()));
        // the units given back may serve the next customer waiting for them
        serveWaiting(order.productId());
        return get(id);
    }

    /** Waiting quantity per product that no supplier order covers yet: added to the order sheet. */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> awaitedByProduct() {
        Map<UUID, Integer> awaited = new HashMap<>();
        jdbc.sql("""
                        SELECT product_id, sum(quantity) AS qty FROM customer_order
                        WHERE tenant_id = :tenant AND status = 'OPEN'
                        GROUP BY product_id""")
                .param("tenant", TenantContext.requireTenant().value())
                .query((RowCallbackHandler) rs -> awaited.put(rs.getObject("product_id", UUID.class),
                        rs.getInt("qty")));
        return awaited;
    }

    // ------------------------------------------------------------------ arrivals

    /** A delivery came in: the oldest orders it can serve in full are reserved and their customers told. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStockReceived(StockReceived event) {
        serveWaiting(event.productId());
    }

    private void serveWaiting(UUID productId) {
        List<OrderView> waiting = jdbc.sql(SELECT + """
                         WHERE o.tenant_id = :tenant AND o.product_id = :product AND o.status = 'OPEN'
                         ORDER BY o.created_at, o.number""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("product", productId)
                .query(this::map).list();
        if (waiting.isEmpty()) {
            return;
        }
        int available = ledger.available(productId);
        List<UUID> served = CustomerOrderAllocation.served(waiting.stream()
                .map(o -> new CustomerOrderAllocation.Waiting(o.id(), o.quantity())).toList(), available);
        if (served.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        var actor = TenantContext.requireTenantUser();
        var notify = followUp.effective().values();
        for (OrderView order : waiting) {
            if (!served.contains(order.id())) {
                continue;
            }
            ledger.reserve(productId, order.quantity(), reference(order.id(), actor.userId()));
            UUID message = null;
            if (order.customerId() != null && notify.enabled() && notify.productWishEnabled()) {
                message = messages.queue(new CustomerMessageService.Draft(order.customerId(),
                        MessageKind.PRODUCT_AVAILABLE, "ORDER:" + order.id(), "CUSTOMER_ORDER", order.id(),
                        Map.of("produit", order.designation()))).orElse(null);
            }
            jdbc.sql("""
                            UPDATE customer_order SET status = 'READY', ready_at = :at, version = version + 1
                            WHERE id = :id AND status = 'OPEN'""")
                    .param("id", order.id()).param("at", Timestamp.from(now))
                    .update();
            audit.record("CUSTOMER_ORDER_READY", "CUSTOMER_ORDER", order.id(), Map.of(
                    "quantity", Integer.toString(order.quantity()),
                    "notified", Boolean.toString(message != null)));
        }
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<OrderView> list(CustomerOrderStatus status, CustomerOrderKind kind, UUID customerId, boolean pending,
                                int limit) {
        return jdbc.sql(SELECT + """
                         WHERE o.tenant_id = :tenant
                           AND (:status::text IS NULL OR o.status = :status::text)
                           AND (:kind::text IS NULL OR o.kind = :kind::text)
                           AND (:customer::uuid IS NULL OR o.customer_id = :customer::uuid)
                           AND (NOT :pending OR o.status IN ('OPEN', 'READY'))
                         ORDER BY CASE o.status WHEN 'READY' THEN 0 WHEN 'OPEN' THEN 1 ELSE 2 END, o.created_at DESC
                         LIMIT :limit""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("status", status == null ? null : status.name())
                .param("kind", kind == null ? null : kind.name())
                .param("customer", customerId)
                .param("pending", pending)
                .param("limit", Math.clamp(limit, 1, 500))
                .query(this::map).list();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'OPEN') AS open,
                               count(*) FILTER (WHERE status = 'READY') AS ready,
                               count(*) FILTER (WHERE kind = 'VOUCHER' AND status IN ('OPEN', 'READY')) AS vouchers,
                               count(*) FILTER (WHERE kind = 'SPECIAL' AND status IN ('OPEN', 'READY')) AS special
                        FROM customer_order WHERE tenant_id = :tenant""")
                .param("tenant", TenantContext.requireTenant().value())
                .query((rs, rowNum) -> new Summary(rs.getInt("open"), rs.getInt("ready"), rs.getInt("vouchers"),
                        rs.getInt("special")))
                .single();
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID id) {
        return find(id).orElseThrow(() -> new NotFoundException("Customer order", id));
    }

    private Optional<OrderView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id").param("id", id).query(this::map).optional();
    }

    private static final String SELECT = """
            SELECT o.id, o.number, o.kind, o.status, o.customer_id, c.display_name AS customer_name, o.holder_name,
                   coalesce(o.holder_phone, c.phone) AS holder_phone, o.product_id, p.designation, p.cip13, o.quantity,
                   o.unit_price_cents, o.sale_id, s.ticket_number, o.delivered_sale_id, o.purchase_order_id,
                   po.number AS po_number, o.notes, u.display_name AS created_by, o.created_at, o.ready_at,
                   o.delivered_at, o.cancelled_at, o.cancel_reason
            FROM customer_order o
            JOIN product p ON p.id = o.product_id
            LEFT JOIN customer c ON c.id = o.customer_id
            LEFT JOIN sale s ON s.id = o.sale_id
            LEFT JOIN purchase_order po ON po.id = o.purchase_order_id
            LEFT JOIN app_user u ON u.id = o.created_by
            """;

    private OrderView map(ResultSet rs, int rowNum) throws SQLException {
        return new OrderView(rs.getObject("id", UUID.class), rs.getLong("number"),
                CustomerOrderKind.valueOf(rs.getString("kind")), CustomerOrderStatus.valueOf(rs.getString("status")),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name"), rs.getString("holder_name"),
                rs.getString("holder_phone"), rs.getObject("product_id", UUID.class), rs.getString("designation"),
                rs.getString("cip13"), rs.getInt("quantity"), (Long) rs.getObject("unit_price_cents"),
                rs.getObject("sale_id", UUID.class), rs.getString("ticket_number"),
                rs.getObject("delivered_sale_id", UUID.class), rs.getObject("purchase_order_id", UUID.class),
                rs.getString("po_number"), rs.getString("notes"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "ready_at"), instant(rs, "delivered_at"),
                instant(rs, "cancelled_at"), rs.getString("cancel_reason"));
    }

    // ------------------------------------------------------------------ helpers

    private static String collectRefusal(OrderView order, SaleDraft.Line line) {
        if (order.kind() != CustomerOrderKind.SPECIAL) {
            return "La commande n° " + order.number() + " est un bon déjà payé : remettez-le depuis la liste des bons";
        }
        if (!order.status().pending()) {
            return "La commande n° " + order.number() + " est déjà soldée";
        }
        if (!order.productId().equals(line.productId())) {
            return "La commande n° " + order.number() + " porte sur un autre produit";
        }
        if (line.quantity() < order.quantity()) {
            return "La commande n° " + order.number() + " porte sur " + order.quantity() + " unité(s)";
        }
        return null;
    }

    private void insert(UUID id, long number, CustomerOrderKind kind, UUID customerId, String holderName,
                        String holderPhone, UUID productId, int quantity, Long unitPriceCents, UUID saleId,
                        UUID saleLineId, String notes, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO customer_order (id, tenant_id, number, kind, status, customer_id, holder_name,
                                                    holder_phone, product_id, quantity, unit_price_cents, sale_id,
                                                    sale_line_id, notes, created_by, created_at)
                        VALUES (:id, :tenant, :number, :kind, 'OPEN', :customer, :holderName, :holderPhone, :product,
                                :quantity, :price, :sale, :saleLine, :notes, :by, :at)""")
                .param("id", id)
                .param("tenant", TenantContext.requireTenant().value())
                .param("number", number)
                .param("kind", kind.name())
                .param("customer", customerId)
                .param("holderName", holderName)
                .param("holderPhone", holderPhone)
                .param("product", productId)
                .param("quantity", quantity)
                .param("price", unitPriceCents)
                .param("sale", saleId)
                .param("saleLine", saleLineId)
                .param("notes", notes)
                .param("by", createdBy)
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }

    private void close(UUID id, String reason) {
        jdbc.sql("""
                        UPDATE customer_order SET status = 'CANCELLED', cancelled_at = :at, cancel_reason = :reason,
                                                  version = version + 1
                        WHERE id = :id""")
                .param("id", id).param("at", Timestamp.from(clock.instant()))
                .param("reason", reason.length() > 255 ? reason.substring(0, 255) : reason)
                .update();
    }

    private StockLedger.Reference reference(UUID orderId, UUID userId) {
        return new StockLedger.Reference(REFERENCE_TYPE, orderId, clock.instant(), userId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
