package com.pharmacore.control.application;

import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narcotics register ("registre des stupéfiants") as seen from the stock: for each narcotic, every
 * movement of its lots over the period with the running theoretical balance (opening balance from
 * the ledger, then each entry and exit), and every physical count inserted at its date with what
 * was expected, what was counted, the gap, and the signatures (who counted, who validated, who gave
 * the second validation). The last count's figures sit next to the theoretical balance of today.
 */
@Service
public class NarcoticsRegisterService {

    static final int MAX_DAYS = 366;
    static final int MAX_ROWS = 500;

    /** One line of the register: a movement, or a count (type {@code COUNT}). */
    public record Entry(Instant at, String type, int quantity, int balance, String reference, String recordedBy,
                        Integer expected, Integer counted, Integer gap, String countedBy, String validatedBy,
                        String approvedBy) {
    }

    public record LastCount(Instant validatedAt, int expected, int counted, int gap, String countedBy,
                            String validatedBy, String approvedBy) {
    }

    public record Product(UUID productId, String designation, String cip13, String locationCode,
                          String locationKind, int openingBalance, int theoreticalBalance, int onHand,
                          LastCount lastCount, int unitsSinceLastCount, boolean gapAtLastCount, List<Entry> entries) {
    }

    public record Register(LocalDate from, LocalDate to, List<Product> products) {
    }

    private final TenantRepository tenants;
    private final JdbcClient jdbc;

    public NarcoticsRegisterService(TenantRepository tenants, JdbcClient jdbc) {
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Register register(LocalDate fromDate, LocalDate toDate) {
        if (toDate.isBefore(fromDate) || ChronoUnit.DAYS.between(fromDate, toDate) > MAX_DAYS) {
            throw new BusinessRuleException("invalid-range", "Période invalide (un an au plus)");
        }
        ZoneId zone = tenants.findById(TenantContext.requireTenant().value()).map(t -> ZoneId.of(t.timeZone()))
                .orElse(ZoneId.of("UTC"));
        Timestamp from = Timestamp.from(fromDate.atStartOfDay(zone).toInstant());
        Timestamp to = Timestamp.from(toDate.plusDays(1).atStartOfDay(zone).toInstant());

        record Head(UUID productId, String designation, String cip13, String locationCode, String locationKind,
                    int opening, int theoretical, int onHand, int sinceLast) {
        }
        List<Head> heads = jdbc.sql("""
                        SELECT p.id, p.designation, p.cip13, loc.code AS location_code, loc.kind AS location_kind,
                               coalesce((SELECT sum(m.quantity) FROM stock_movement m
                                         WHERE m.stock_id = s.id AND m.lot_id IS NOT NULL AND m.occurred_at < :from), 0)
                                   AS opening,
                               coalesce((SELECT sum(m.quantity) FROM stock_movement m
                                         WHERE m.stock_id = s.id AND m.lot_id IS NOT NULL), 0) AS theoretical,
                               coalesce((SELECT sum(l.quantity) FROM stock_lot l WHERE l.stock_id = s.id), 0) AS on_hand,
                               coalesce((SELECT sum(m.quantity) FROM stock_movement m
                                         WHERE m.stock_id = s.id AND m.lot_id IS NOT NULL
                                           AND m.movement_type <> 'COUNT_ADJUSTMENT'
                                           AND m.occurred_at > (SELECT max(c.validated_at) FROM stock_count c
                                                                JOIN stock_count_line cl ON cl.count_id = c.id
                                                                WHERE c.status = 'VALIDATED' AND cl.product_id = p.id
                                                                  AND cl.counted_quantity IS NOT NULL)), 0)
                                   AS since_last
                        FROM stock s
                        JOIN product p ON p.id = s.product_id
                        LEFT JOIN storage_location loc ON loc.id = s.location_id
                        WHERE p.listing = 'STUPEFIANT'
                        ORDER BY p.designation""")
                .param("from", from)
                .query((rs, rowNum) -> new Head(rs.getObject("id", UUID.class), rs.getString("designation"),
                        rs.getString("cip13"), rs.getString("location_code"), rs.getString("location_kind"),
                        rs.getInt("opening"), rs.getInt("theoretical"), rs.getInt("on_hand"), rs.getInt("since_last")))
                .list();
        if (heads.isEmpty()) {
            return new Register(fromDate, toDate, List.of());
        }
        UUID[] productIds = heads.stream().map(Head::productId).toArray(UUID[]::new);

        // Lot movements of the period, counts excluded (they are shown as count lines)
        Map<UUID, List<Entry>> movements = new HashMap<>();
        jdbc.sql("""
                        SELECT s.product_id, m.occurred_at, m.movement_type, m.quantity, u.display_name,
                               CASE WHEN m.reference_type IN ('SALE', 'SALE_CANCELLATION') THEN 'Ticket ' || sa.ticket_number
                                    WHEN m.reference_type = 'SALE_RETURN' THEN 'Retour ticket ' || rs.ticket_number
                                    WHEN m.reference_type = 'WRITE_OFF' THEN initcap(lower(w.kind)) || ' : ' || w.reason
                                    WHEN m.movement_type = 'RECEIPT' THEN 'Lot ' || l.lot_number
                                    ELSE 'Lot ' || l.lot_number END AS reference
                        FROM stock_movement m
                        JOIN stock s ON s.id = m.stock_id
                        JOIN stock_lot l ON l.id = m.lot_id
                        LEFT JOIN app_user u ON u.id = m.recorded_by
                        LEFT JOIN sale sa ON sa.id = m.reference_id AND m.reference_type IN ('SALE', 'SALE_CANCELLATION')
                        LEFT JOIN sale_return r ON r.id = m.reference_id AND m.reference_type = 'SALE_RETURN'
                        LEFT JOIN sale rs ON rs.id = r.sale_id
                        LEFT JOIN stock_write_off w ON w.id = m.reference_id AND m.reference_type = 'WRITE_OFF'
                        WHERE s.product_id = ANY(:products) AND m.occurred_at >= :from AND m.occurred_at < :to
                          AND m.movement_type <> 'COUNT_ADJUSTMENT'
                        ORDER BY m.occurred_at, m.id""")
                .param("products", productIds)
                .param("from", from)
                .param("to", to)
                .query((RowCallbackHandler) rs -> movements
                        .computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new ArrayList<>())
                        .add(new Entry(rs.getTimestamp("occurred_at").toInstant(), rs.getString("movement_type"),
                                rs.getInt("quantity"), 0, rs.getString("reference"), rs.getString("display_name"),
                                null, null, null, null, null, null)));

        // Counts of these products (validated), with their signatures and the adjustment they caused
        record Count(UUID productId, Instant at, int expected, int counted, int adjusted, String countedBy,
                     String validatedBy, String approvedBy, String label) {
        }
        List<Count> counts = jdbc.sql("""
                        SELECT l.product_id, c.validated_at, c.label,
                               sum(l.expected_quantity) AS expected, sum(l.counted_quantity) AS counted,
                               coalesce((SELECT sum(m.quantity) FROM stock_movement m JOIN stock st ON st.id = m.stock_id
                                         WHERE m.movement_type = 'COUNT_ADJUSTMENT' AND m.reference_id = c.id
                                           AND st.product_id = l.product_id), 0) AS adjusted,
                               string_agg(DISTINCT cu.display_name, ', ') AS counted_by,
                               vu.display_name AS validated_by,
                               (SELECT string_agg(au.display_name, ', ') FROM control_approval a
                                JOIN app_user au ON au.id = a.approved_by
                                WHERE a.resource_type = 'STOCK_COUNT' AND a.resource_id = c.id) AS approved_by
                        FROM stock_count c
                        JOIN stock_count_line l ON l.count_id = c.id
                        LEFT JOIN app_user cu ON cu.id = l.counted_by
                        LEFT JOIN app_user vu ON vu.id = c.validated_by
                        WHERE c.status = 'VALIDATED' AND l.product_id = ANY(:products) AND l.counted_quantity IS NOT NULL
                        GROUP BY l.product_id, c.id, c.validated_at, c.label, vu.display_name
                        ORDER BY c.validated_at""")
                .param("products", productIds)
                .query((rs, rowNum) -> new Count(rs.getObject("product_id", UUID.class),
                        rs.getTimestamp("validated_at").toInstant(), rs.getInt("expected"), rs.getInt("counted"),
                        rs.getInt("adjusted"), rs.getString("counted_by"), rs.getString("validated_by"),
                        rs.getString("approved_by"), rs.getString("label")))
                .list();
        Map<UUID, List<Count>> countsByProduct = new LinkedHashMap<>();
        counts.forEach(c -> countsByProduct.computeIfAbsent(c.productId(), id -> new ArrayList<>()).add(c));

        List<Product> products = new ArrayList<>();
        for (Head head : heads) {
            List<Count> productCounts = countsByProduct.getOrDefault(head.productId(), List.of());
            List<Entry> rows = new ArrayList<>(movements.getOrDefault(head.productId(), List.of()));
            for (Count c : productCounts) {
                if (!c.at().isBefore(from.toInstant()) && c.at().isBefore(to.toInstant())) {
                    rows.add(new Entry(c.at(), "COUNT", c.adjusted(), 0, c.label(), c.validatedBy(), c.expected(),
                            c.counted(), c.counted() - c.expected(), c.countedBy(), c.validatedBy(), c.approvedBy()));
                }
            }
            rows.sort(Comparator.comparing(Entry::at));
            int balance = head.opening();
            List<Entry> withBalance = new ArrayList<>();
            for (Entry row : rows) {
                balance += row.quantity();
                withBalance.add(new Entry(row.at(), row.type(), row.quantity(), balance, row.reference(),
                        row.recordedBy(), row.expected(), row.counted(), row.gap(), row.countedBy(),
                        row.validatedBy(), row.approvedBy()));
            }
            if (withBalance.size() > MAX_ROWS) {
                withBalance = withBalance.subList(withBalance.size() - MAX_ROWS, withBalance.size());
            }
            Count last = productCounts.isEmpty() ? null : productCounts.getLast();
            int sinceLast = last == null ? 0 : head.sinceLast();
            products.add(new Product(head.productId(), head.designation(), head.cip13(), head.locationCode(),
                    head.locationKind(), head.opening(), head.theoretical(), head.onHand(),
                    last == null ? null : new LastCount(last.at(), last.expected(), last.counted(),
                            last.counted() - last.expected(), last.countedBy(), last.validatedBy(), last.approvedBy()),
                    sinceLast, last != null && last.counted() != last.expected(), List.copyOf(withBalance)));
        }
        return new Register(fromDate, toDate, products);
    }
}
