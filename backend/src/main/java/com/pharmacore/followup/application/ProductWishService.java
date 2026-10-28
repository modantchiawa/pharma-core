package com.pharmacore.followup.application;

import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.followup.domain.MessageKind;
import com.pharmacore.inventory.application.StockReceived;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Tell me when it is back": a customer waits for a product out of stock. The arrival of the
 * product (any receipt) queues the message in the same transaction and closes the wish.
 */
@Service
public class ProductWishService {

    public record WishView(UUID id, UUID customerId, String customerName, String customerPhone, UUID productId,
                           String designation, int quantity, String status, Instant requestedAt, Instant notifiedAt) {
    }

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final CustomerMessageService messages;
    private final FollowUpSettingsService settings;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ProductWishService(CustomerRepository customers, ProductRepository products,
                              CustomerMessageService messages, FollowUpSettingsService settings, AuditTrail audit,
                              JdbcClient jdbc, Clock clock) {
        this.customers = customers;
        this.products = products;
        this.messages = messages;
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public WishView create(UUID customerId, UUID productId, int quantity) {
        var customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        var product = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
        if (!customer.messagingConsent()) {
            throw new BusinessRuleException("no-consent",
                    "Ce client n'a pas accepté d'être contacté : cochez son consentement dans sa fiche");
        }
        var user = TenantContext.requireTenantUser();
        UUID existing = jdbc.sql("""
                        SELECT id FROM customer_product_wish
                        WHERE tenant_id = :tenant AND customer_id = :customer AND product_id = :product AND status = 'WAITING'
                        LIMIT 1""")
                .param("tenant", user.tenant().value()).param("customer", customerId).param("product", productId)
                .query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return get(existing);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_product_wish (id, tenant_id, customer_id, product_id, quantity, status,
                                                           requested_by, requested_at)
                        VALUES (:id, :tenant, :customer, :product, :quantity, 'WAITING', :by, :at)""")
                .param("id", id).param("tenant", user.tenant().value()).param("customer", customerId)
                .param("product", productId).param("quantity", Math.clamp(quantity, 1, 999))
                .param("by", user.userId()).param("at", Timestamp.from(clock.instant()))
                .update();
        audit.record("PRODUCT_WISH_CREATED", "CUSTOMER_PRODUCT_WISH", id, Map.of(
                "customer", customerId.toString(), "product", product.designation()));
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<WishView> list(String status, int limit) {
        return jdbc.sql("""
                        SELECT w.id, w.customer_id, c.display_name, c.phone, w.product_id, p.designation, w.quantity,
                               w.status, w.requested_at, w.notified_at
                        FROM customer_product_wish w
                        JOIN customer c ON c.id = w.customer_id
                        JOIN product p ON p.id = w.product_id
                        WHERE w.tenant_id = :tenant AND (:status::text IS NULL OR w.status = :status::text)
                        ORDER BY w.requested_at DESC
                        LIMIT :limit""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("status", status)
                .param("limit", Math.clamp(limit, 1, 500))
                .query(this::map)
                .list();
    }

    @Transactional(readOnly = true)
    public WishView get(UUID id) {
        return jdbc.sql("""
                        SELECT w.id, w.customer_id, c.display_name, c.phone, w.product_id, p.designation, w.quantity,
                               w.status, w.requested_at, w.notified_at
                        FROM customer_product_wish w
                        JOIN customer c ON c.id = w.customer_id
                        JOIN product p ON p.id = w.product_id
                        WHERE w.id = :id""")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new NotFoundException("Wish", id));
    }

    @Transactional
    public WishView cancel(UUID id) {
        int updated = jdbc.sql("""
                        UPDATE customer_product_wish SET status = 'CANCELLED', version = version + 1
                        WHERE id = :id AND status = 'WAITING'""")
                .param("id", id).update();
        if (updated == 0) {
            throw new BusinessRuleException("wish-not-waiting", "Cette attente n'est plus active");
        }
        audit.record("PRODUCT_WISH_CANCELLED", "CUSTOMER_PRODUCT_WISH", id, Map.of());
        return get(id);
    }

    /** Stock came in: every customer waiting for the product gets their message, in the same transaction. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStockReceived(StockReceived event) {
        if (!settings.effective().values().productWishEnabled() || !settings.effective().values().enabled()) {
            return;
        }
        List<WishView> waiting = jdbc.sql("""
                        SELECT w.id, w.customer_id, c.display_name, c.phone, w.product_id, p.designation, w.quantity,
                               w.status, w.requested_at, w.notified_at
                        FROM customer_product_wish w
                        JOIN customer c ON c.id = w.customer_id
                        JOIN product p ON p.id = w.product_id
                        WHERE w.tenant_id = :tenant AND w.product_id = :product AND w.status = 'WAITING'
                        ORDER BY w.requested_at""")
                .param("tenant", event.tenantId())
                .param("product", event.productId())
                .query(this::map)
                .list();
        Instant now = clock.instant();
        for (WishView wish : waiting) {
            UUID messageId = messages.queue(new CustomerMessageService.Draft(wish.customerId(),
                    MessageKind.PRODUCT_AVAILABLE, "WISH:" + wish.id(), "CUSTOMER_PRODUCT_WISH", wish.id(),
                    Map.of("produit", wish.designation()))).orElse(null);
            jdbc.sql("""
                            UPDATE customer_product_wish SET status = 'NOTIFIED', notified_at = :at, message_id = :message,
                                                             version = version + 1
                            WHERE id = :id""")
                    .param("id", wish.id()).param("at", Timestamp.from(now)).param("message", messageId)
                    .update();
        }
    }

    private WishView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WishView(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("display_name"), rs.getString("phone"), rs.getObject("product_id", UUID.class),
                rs.getString("designation"), rs.getInt("quantity"), rs.getString("status"),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("notified_at") == null ? null : rs.getTimestamp("notified_at").toInstant());
    }
}
