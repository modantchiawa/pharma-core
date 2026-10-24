package com.pharmacore.network.application;

import com.pharmacore.catalog.application.CatalogService;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.network.domain.AvailabilityLevel;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emergency availability lookup across partner pharmacies. The heavy lifting and the security
 * boundary live in the {@code network_stock_availability} SECURITY DEFINER function: the
 * requester is taken from the session, only partners sharing their stock are visible, and no
 * price, cost, lot or sales figure can cross tenants.
 */
@Service
public class NetworkStockQueryService {

    static final int MAX_PRODUCTS = 10;

    private final JdbcClient jdbc;
    private final QueryRateLimiter rateLimiter;
    private final AuditTrail audit;
    private final CatalogService catalog;

    public NetworkStockQueryService(JdbcClient jdbc, QueryRateLimiter rateLimiter, AuditTrail audit,
                                    CatalogService catalog) {
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.catalog = catalog;
    }

    @Transactional
    public List<PartnerAvailability> availability(UUID productId) {
        var tenant = TenantContext.requireTenant();
        if (!rateLimiter.tryAcquire(tenant)) {
            throw new RateLimitExceededException("Too many network stock queries, retry in a minute");
        }
        List<PartnerAvailability> partners = partners(productId);
        // Who looked for what is traceable (searches can reveal patient needs).
        audit.record("NETWORK_STOCK_QUERIED", "PRODUCT", productId, Map.of(
                "partnersAnswering", Integer.toString(partners.size()),
                "partnersWithStock", Long.toString(partners.stream()
                        .filter(p -> p.level() != AvailabilityLevel.OUT_OF_STOCK).count())));
        return partners;
    }

    /** "Who has it?" by name, CIP or molecule: the partners holding each matching product. */
    @Transactional
    public List<ProductPartners> search(String query) {
        var tenant = TenantContext.requireTenant();
        String term = query == null ? "" : query.trim();
        if (term.length() < 2 || term.length() > 100) {
            throw new BusinessRuleException("query-length", "Saisissez entre 2 et 100 caract\u00e8res");
        }
        if (!rateLimiter.tryAcquire(tenant)) {
            throw new RateLimitExceededException("Too many network stock queries, retry in a minute");
        }
        List<ProductPartners> result = new ArrayList<>();
        for (ProductEntity product : catalog.search(term, MAX_PRODUCTS)) {
            List<PartnerAvailability> holders = partners(product.id()).stream()
                    .filter(p -> p.level() != AvailabilityLevel.OUT_OF_STOCK)
                    .toList();
            if (!holders.isEmpty()) {
                result.add(new ProductPartners(product.id(), product.cip13(), product.designation(),
                        product.data().dci(), holders));
            }
        }
        audit.record("NETWORK_STOCK_SEARCHED", "PRODUCT", "search", Map.of(
                "queryLength", Integer.toString(term.length()),
                "productsFound", Integer.toString(result.size())));
        return result;
    }

    /** What one partner pharmacy holds among the products matching the query. */
    @Transactional
    public List<PartnerProduct> partnerStock(UUID partnerTenantId, String query) {
        var tenant = TenantContext.requireTenant();
        String term = query == null ? "" : query.trim();
        if (term.length() < 2 || term.length() > 100) {
            throw new BusinessRuleException("query-length", "Saisissez entre 2 et 100 caract\u00e8res");
        }
        if (!rateLimiter.tryAcquire(tenant)) {
            throw new RateLimitExceededException("Too many network stock queries, retry in a minute");
        }
        List<PartnerProduct> products = jdbc.sql("SELECT * FROM partner_stock_availability(:partner, :query)")
                .param("partner", partnerTenantId)
                .param("query", term)
                .query((rs, rowNum) -> {
                    int quantity = rs.getInt("available_quantity");
                    int cap = rs.getInt("quantity_cap");
                    return new PartnerProduct(
                            rs.getObject("product_id", UUID.class),
                            rs.getString("cip13"),
                            rs.getString("designation"),
                            rs.getString("dci"),
                            rs.getObject("network_id", UUID.class),
                            rs.getString("network_name"),
                            AvailabilityLevel.of(quantity),
                            quantity,
                            quantity >= cap);
                })
                .list();
        audit.record("NETWORK_PARTNER_STOCK_QUERIED", "TENANT", partnerTenantId, Map.of(
                "queryLength", Integer.toString(term.length()),
                "productsAnswered", Integer.toString(products.size())));
        return products;
    }

    private List<PartnerAvailability> partners(UUID productId) {
        return jdbc.sql("SELECT * FROM network_stock_availability(:product)")
                .param("product", productId)
                .query((rs, rowNum) -> {
                    int quantity = rs.getInt("available_quantity");
                    int cap = rs.getInt("quantity_cap");
                    return new PartnerAvailability(
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("pharmacy_name"),
                            rs.getString("city"),
                            rs.getString("phone"),
                            rs.getObject("network_id", UUID.class),
                            rs.getString("network_name"),
                            AvailabilityLevel.of(quantity),
                            quantity,
                            quantity >= cap);
                })
                .list()
                .stream()
                .sorted(Comparator.comparing(PartnerAvailability::level).reversed()
                        .thenComparing(PartnerAvailability::pharmacyName))
                .toList();
    }

    /**
     * @param quantity     capped by the network setting, never the exact stock beyond the cap
     * @param capped       true when the real quantity is at least the cap ("10+")
     */
    public record PartnerAvailability(UUID tenantId, String pharmacyName, String city, String phone, UUID networkId,
                                      String networkName, AvailabilityLevel level, int quantity, boolean capped) {
    }

    public record ProductPartners(UUID productId, String cip13, String designation, String dci,
                                  List<PartnerAvailability> partners) {
    }

    public record PartnerProduct(UUID productId, String cip13, String designation, String dci, UUID networkId,
                                 String networkName, AvailabilityLevel level, int quantity, boolean capped) {
    }
}
