package com.pharmacore.catalog.application;

import com.pharmacore.catalog.domain.Cip13;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductEntity.ProductData;
import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.inventory.domain.ProductImageRules;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowCallbackHandler;
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

    /** Product carrying this 13-digit code as CIP13 or, failing that, as EAN13. */
    @Transactional(readOnly = true)
    public java.util.Optional<ProductEntity> findByCode(String code) {
        return products.findByAnyCode(code).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<ProductEntity> search(String term, int limit) {
        return products.search(term.trim(), PageRequest.of(0, Math.clamp(limit, 1, 50)));
    }

    /**
     * Photo versions of the current pharmacy for these products, in one query; empty outside a
     * pharmacy (the platform operator sees the national catalog only).
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> imageVersions(Collection<UUID> productIds) {
        var tenant = TenantContext.current().flatMap(context -> context.actor().tenantId());
        if (tenant.isEmpty() || productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> versions = new HashMap<>();
        jdbc.sql("""
                        SELECT product_id, left(sha256, :versionLength) AS image_version FROM product_image
                        WHERE tenant_id = :tenant AND product_id = ANY(:ids) AND sha256 IS NOT NULL""")
                .param("tenant", tenant.get().value())
                .param("ids", productIds.toArray(UUID[]::new))
                .param("versionLength", ProductImageRules.VERSION_LENGTH)
                .query((RowCallbackHandler) rs -> versions.put(rs.getObject("product_id", UUID.class),
                        rs.getString("image_version")));
        return versions;
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
                                   loc.code AS location_code, loc.name AS location_name, loc.kind AS location_kind,
                                   s.location_detail, left(pi.sha256, :versionLength) AS image_version,
                                   -- renaming a location or changing a photo re-emits the products concerned
                                   GREATEST(p.updated_at, coalesce(s.updated_at, p.updated_at),
                                            coalesce(loc.updated_at, p.updated_at),
                                            coalesce(pi.updated_at, p.updated_at)) AS changed_at
                            FROM product p
                            LEFT JOIN stock s ON s.product_id = p.id AND s.tenant_id = :tenant
                            LEFT JOIN storage_location loc ON loc.id = s.location_id
                            LEFT JOIN product_image pi ON pi.product_id = p.id AND pi.tenant_id = :tenant
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
                .param("versionLength", ProductImageRules.VERSION_LENGTH)
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
                        rs.getString("location_code"),
                        rs.getString("location_name"),
                        rs.getString("location_kind"),
                        rs.getString("location_detail"),
                        rs.getTimestamp("changed_at").toInstant(),
                        rs.getString("image_version")))
                .list();
        boolean hasMore = items.size() == pageSize;
        var last = items.isEmpty() ? null : items.getLast();
        return new PosCatalogPage(items,
                last == null ? since : last.changedAt(),
                last == null ? afterProductId : last.productId(),
                hasMore);
    }

    /**
     * Therapeutic equivalents of a product for the counter: same active substance (DCI) and, when
     * the product states one, same dosage; the pharmacy's own price and availability come along so
     * the seller can offer a substitute in stock when the requested brand is missing.
     */
    @Transactional(readOnly = true)
    public List<Alternative> alternatives(UUID productId) {
        UUID tenant = TenantContext.requireTenant().value();
        return jdbc.sql("""
                        SELECT alt.id, alt.cip13, alt.designation, alt.dosage, alt.pharmaceutical_form, alt.laboratory,
                               coalesce(s.selling_price_cents, alt.public_price_cents) AS unit_price_cents,
                               GREATEST(coalesce(l.qty, 0) - coalesce(s.reserved_quantity, 0), 0) AS available,
                               left(pi.sha256, :versionLength) AS image_version
                        FROM product ref
                        JOIN product alt ON alt.id <> ref.id AND alt.active
                             AND lower(alt.dci) = lower(ref.dci)
                             AND (ref.dosage IS NULL OR alt.dosage IS NULL OR lower(alt.dosage) = lower(ref.dosage))
                        LEFT JOIN stock s ON s.product_id = alt.id AND s.tenant_id = :tenant
                        LEFT JOIN product_image pi ON pi.product_id = alt.id AND pi.tenant_id = :tenant
                        LEFT JOIN LATERAL (
                            SELECT sum(quantity) AS qty FROM stock_lot sl
                            WHERE sl.stock_id = s.id AND sl.quantity > 0 AND sl.expiry_date > current_date
                        ) l ON true
                        WHERE ref.id = :product AND ref.dci IS NOT NULL
                        ORDER BY available DESC, unit_price_cents NULLS LAST, alt.designation
                        LIMIT 20""")
                .param("tenant", tenant)
                .param("product", productId)
                .param("versionLength", ProductImageRules.VERSION_LENGTH)
                .query((rs, rowNum) -> new Alternative(
                        rs.getObject("id", UUID.class), rs.getString("cip13"), rs.getString("designation"),
                        rs.getString("dosage"), rs.getString("pharmaceutical_form"), rs.getString("laboratory"),
                        (Long) rs.getObject("unit_price_cents"), rs.getInt("available"),
                        rs.getString("image_version")))
                .list();
    }

    public record Alternative(UUID productId, String cip13, String designation, String dosage, String pharmaceuticalForm,
                              String laboratory, Long unitPriceCents, int availableQuantity, String imageVersion) {
    }

    /**
     * @param locationCode where the product is stored in this pharmacy, {@code null} when not placed
     * @param imageVersion version of the pharmacy's photo of the product, {@code null} without photo
     */
    public record PosCatalogItem(UUID productId, String cip13, String ean13, String designation, String dci,
                                 String listing, java.math.BigDecimal vatRate, Long unitPriceCents,
                                 int availableQuantity, boolean active, String locationCode, String locationName,
                                 String locationKind, String locationDetail, Instant changedAt,
                                 String imageVersion) {
    }

    public record PosCatalogPage(List<PosCatalogItem> items, Instant nextSince, UUID nextAfter, boolean hasMore) {
    }
}
