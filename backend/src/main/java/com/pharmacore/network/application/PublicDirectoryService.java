package com.pharmacore.network.application;

import com.pharmacore.guard.domain.GuardShareText;
import com.pharmacore.network.domain.PublicAvailabilityLevel;
import com.pharmacore.shared.web.BusinessRuleException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unauthenticated directory served by the cloud: listed pharmacies, where a product can be found
 * (level only), and the published duty rosters. Everything crosses the tenant boundary through
 * SECURITY DEFINER functions whose owner only sees public columns.
 */
@Service
public class PublicDirectoryService {

    static final int MIN_QUERY_LENGTH = 3;
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Africa/Niamey");

    private final JdbcClient jdbc;
    private final PublicDirectoryUsage usage;

    public PublicDirectoryService(JdbcClient jdbc, PublicDirectoryUsage usage) {
        this.jdbc = jdbc;
        this.usage = usage;
    }

    @Transactional(readOnly = true)
    public List<PublicPharmacy> pharmacies(String city) {
        List<PublicPharmacy> result = jdbc.sql("SELECT * FROM public_pharmacies(:city::text)")
                .param("city", blankToNull(city))
                .query((rs, rowNum) -> new PublicPharmacy(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("pharmacy_name"),
                        rs.getString("address"),
                        rs.getString("city"),
                        rs.getString("phone"),
                        rs.getString("whatsapp"),
                        rs.getString("opening_hours"),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude"),
                        rs.getBoolean("shares_availability")))
                .list();
        usage.count("pharmacies", !result.isEmpty());
        return result;
    }

    /** Products matching the query, each with the listed pharmacies that have it. */
    @Transactional(readOnly = true)
    public List<ProductAvailability> availability(String query, String city) {
        String term = query == null ? "" : query.trim();
        if (term.length() < MIN_QUERY_LENGTH) {
            throw new BusinessRuleException("query-too-short", "Saisissez au moins 3 caractères");
        }
        Map<UUID, ProductAvailability> byProduct = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM public_availability(:query, :city::text)")
                .param("query", term)
                .param("city", blankToNull(city))
                .query(rs -> {
                    UUID productId = rs.getObject("product_id", UUID.class);
                    var ref = new ProductRef(productId, rs.getString("cip13"), rs.getString("designation"),
                            rs.getString("dci"));
                    var product = byProduct.computeIfAbsent(productId,
                            id -> new ProductAvailability(ref, new ArrayList<>()));
                    product.pharmacies().add(new PharmacyAvailability(
                            rs.getObject("tenant_id", UUID.class),
                            rs.getString("pharmacy_name"),
                            rs.getString("address"),
                            rs.getString("city"),
                            rs.getString("phone"),
                            rs.getString("whatsapp"),
                            rs.getBigDecimal("latitude"),
                            rs.getBigDecimal("longitude"),
                            PublicAvailabilityLevel.parse(rs.getString("level"))));
                });
        List<ProductAvailability> result = List.copyOf(byProduct.values());
        usage.count("availability", !result.isEmpty());
        return result;
    }

    /** Pharmacies on duty at the given instant. */
    @Transactional(readOnly = true)
    public List<PublicGuardSlot> guards(String city, Instant at) {
        List<PublicGuardSlot> result = guardSlots(city, at, at.plusSeconds(1));
        usage.count("guards", !result.isEmpty());
        return result;
    }

    /** Text of the day's roster, in the time zone of the pharmacies concerned. */
    @Transactional(readOnly = true)
    public String guardsShareText(String city, LocalDate date) {
        // A first pass at the default zone gives us the pharmacies' own zone for the exact day bounds
        ZoneId zone = guardSlots(city, date.atStartOfDay(DEFAULT_ZONE).toInstant().minusSeconds(12 * 3600),
                date.plusDays(1).atStartOfDay(DEFAULT_ZONE).toInstant().plusSeconds(12 * 3600)).stream()
                .map(PublicGuardSlot::timeZone)
                .filter(tz -> tz != null && !tz.isBlank())
                .map(ZoneId::of)
                .findFirst()
                .orElse(DEFAULT_ZONE);
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<PublicGuardSlot> slots = guardSlots(city, from, to);
        usage.count("guards.share", !slots.isEmpty());
        return GuardShareText.render(blankToNull(city), date, zone, slots.stream()
                .map(s -> new GuardShareText.Entry(s.pharmacyName(), s.address(), s.phone(), s.type(), s.startsAt(),
                        s.endsAt()))
                .toList());
    }

    private List<PublicGuardSlot> guardSlots(String city, Instant from, Instant to) {
        return jdbc.sql("SELECT * FROM public_guards(:city::text, :from, :to)")
                .param("city", blankToNull(city))
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, rowNum) -> new PublicGuardSlot(
                        rs.getObject("slot_id", UUID.class),
                        rs.getString("network_name"),
                        rs.getString("guard_type"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at").toInstant(),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("pharmacy_name"),
                        rs.getString("address"),
                        rs.getString("city"),
                        rs.getString("phone"),
                        rs.getString("whatsapp"),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude"),
                        rs.getString("time_zone")))
                .list();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record PublicPharmacy(UUID tenantId, String slug, String pharmacyName, String address, String city,
                                 String phone, String whatsapp, String openingHours, BigDecimal latitude,
                                 BigDecimal longitude, boolean sharesAvailability) {
    }

    public record ProductRef(UUID id, String cip13, String designation, String dci) {
    }

    public record PharmacyAvailability(UUID tenantId, String pharmacyName, String address, String city, String phone,
                                       String whatsapp, BigDecimal latitude, BigDecimal longitude,
                                       PublicAvailabilityLevel level) {
    }

    public record ProductAvailability(ProductRef product, List<PharmacyAvailability> pharmacies) {
    }

    public record PublicGuardSlot(UUID slotId, String networkName, String type, Instant startsAt, Instant endsAt,
                                  UUID tenantId, String pharmacyName, String address, String city, String phone,
                                  String whatsapp, BigDecimal latitude, BigDecimal longitude, String timeZone) {
    }
}
