package com.pharmacore.network.application;

import com.pharmacore.network.domain.AvailabilityLevel;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
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

    private final JdbcClient jdbc;
    private final QueryRateLimiter rateLimiter;
    private final AuditTrail audit;

    public NetworkStockQueryService(JdbcClient jdbc, QueryRateLimiter rateLimiter, AuditTrail audit) {
        this.jdbc = jdbc;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    @Transactional
    public List<PartnerAvailability> availability(UUID productId) {
        var tenant = TenantContext.requireTenant();
        if (!rateLimiter.tryAcquire(tenant)) {
            throw new RateLimitExceededException("Too many network stock queries, retry in a minute");
        }
        List<PartnerAvailability> partners = jdbc.sql("SELECT * FROM network_stock_availability(:product)")
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
        // Who looked for what is traceable (searches can reveal patient needs).
        audit.record("NETWORK_STOCK_QUERIED", "PRODUCT", productId, Map.of(
                "partnersAnswering", Integer.toString(partners.size()),
                "partnersWithStock", Long.toString(partners.stream()
                        .filter(p -> p.level() != AvailabilityLevel.OUT_OF_STOCK).count())));
        return partners;
    }

    /**
     * @param quantity     capped by the network setting, never the exact stock beyond the cap
     * @param capped       true when the real quantity is at least the cap ("10+")
     */
    public record PartnerAvailability(UUID tenantId, String pharmacyName, String city, String phone, UUID networkId,
                                      String networkName, AvailabilityLevel level, int quantity, boolean capped) {
    }
}
