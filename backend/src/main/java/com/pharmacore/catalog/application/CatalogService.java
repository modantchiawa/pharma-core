package com.pharmacore.catalog.application;

import com.pharmacore.catalog.domain.Cip13;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductEntity.ProductData;
import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ProductRepository products;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CatalogService(ProductRepository products, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.products = products;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Platform operation: create or update a national catalog entry identified by its CIP13. */
    @Transactional
    public ProductEntity upsert(String cip13, ProductData data) {
        if (!Cip13.isValid(cip13)) {
            throw new BusinessRuleException("invalid-cip13", "Invalid CIP13 code " + cip13);
        }
        if (data.ean13() != null && !Cip13.isValid(data.ean13())) {
            throw new BusinessRuleException("invalid-ean13", "Invalid EAN13 code " + data.ean13());
        }
        Instant now = clock.instant();
        var existing = products.findByCip13(cip13);
        boolean created = existing.isEmpty();
        var product = existing.orElseGet(() -> new ProductEntity(cip13, now));
        product.describe(data, now);
        products.save(product);
        audit.record(created ? "PRODUCT_CREATED" : "PRODUCT_UPDATED", "PRODUCT", product.id(), Map.of(
                "cip13", cip13,
                "listing", data.listing().name(),
                "publicPriceCents", String.valueOf(data.publicPriceCents()),
                "active", String.valueOf(data.active())));
        return product;
    }

    @Transactional(readOnly = true)
    public List<ProductEntity> search(String term, int limit) {
        return products.search(term.trim(), PageRequest.of(0, Math.clamp(limit, 1, 50)));
    }

    /**
     * Delta feed for the offline POS cache: national catalog merged with the pharmacy's own price
     * and available quantity. Keyset pagination on (changed_at, product_id) keeps it stable while
     * rows keep changing.
     */
    @Transactional(readOnly = true)
    public PosCatalogPage posChanges(Instant since, UUID afterProductId, int limit) {
        UUID tenant = TenantContext.requireTenant().value();
        int pageSize = Math.clamp(limit, 1, 2_000);
        List<PosCatalogItem> items = jdbc.sql("""
                        WITH merged AS (
                            SELECT p.id, p.cip13, p.ean13, p.designation, p.dci, p.listing, p.vat_rate,
                                   coalesce(s.selling_price_cents, p.public_price_cents) AS unit_price_cents,
                                   p.active,
                                   GREATEST(coalesce(l.qty, 0) - coalesce(s.reserved_quantity, 0), 0) AS available,
                                   GREATEST(p.updated_at, coalesce(s.updated_at, p.updated_at)) AS changed_at
                            FROM product p
                            LEFT JOIN stock s ON s.product_id = p.id AND s.tenant_id = :tenant
                            LEFT JOIN LATERAL (
                                SELECT sum(quantity) AS qty FROM stock_lot sl
                                WHERE sl.stock_id = s.id AND sl.quantity > 0 AND sl.expiry_date > current_date
                            ) l ON true
                        )
                        SELECT * FROM merged
                        WHERE (changed_at, id) > (:since, :after)
                        ORDER BY changed_at, id
                        LIMIT :limit""")
                .param("tenant", tenant)
                .param("since", Timestamp.from(since))
                .param("after", afterProductId == null ? new UUID(0L, 0L) : afterProductId)
                .param("limit", pageSize)
                .query((rs, rowNum) -> new PosCatalogItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("cip13"),
                        rs.getString("ean13"),
                        rs.getString("designation"),
                        rs.getString("dci"),
                        rs.getString("listing"),
                        rs.getBigDecimal("vat_rate"),
                        (Long) rs.getObject("unit_price_cents"),
                        rs.getInt("available"),
                        rs.getBoolean("active"),
                        rs.getTimestamp("changed_at").toInstant()))
                .list();
        boolean hasMore = items.size() == pageSize;
        var last = items.isEmpty() ? null : items.getLast();
        return new PosCatalogPage(items,
                last == null ? since : last.changedAt(),
                last == null ? afterProductId : last.productId(),
                hasMore);
    }

    public record PosCatalogItem(UUID productId, String cip13, String ean13, String designation, String dci,
                                 String listing, java.math.BigDecimal vatRate, Long unitPriceCents,
                                 int availableQuantity, boolean active, Instant changedAt) {
    }

    public record PosCatalogPage(List<PosCatalogItem> items, Instant nextSince, UUID nextAfter, boolean hasMore) {
    }
}
