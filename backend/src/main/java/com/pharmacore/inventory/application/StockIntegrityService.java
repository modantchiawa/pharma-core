package com.pharmacore.inventory.application;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that the stock ledger and the shelf quantities still agree. Every quantity change is
 * explained by exactly one movement, so for each lot {@code sum(movements) == stock_lot.quantity}
 * and for each product {@code sum(RESERVATION + RESERVATION_RELEASE) == stock.reserved_quantity}.
 * A mismatch is never corrected silently: it is recorded, audited and shown to the pharmacist,
 * who resolves it with an inventory count.
 */
@Service
public class StockIntegrityService {

    public enum MismatchKind {
        LOT_QUANTITY,
        RESERVED_QUANTITY
    }

    public record Mismatch(MismatchKind kind, UUID stockId, UUID lotId, String designation, String cip13,
                           String lotNumber, int stored, int computed) {
    }

    public record Report(UUID id, Instant runAt, int lotsChecked, int mismatches, List<Mismatch> details) {
    }

    private static final TypeReference<List<Mismatch>> MISMATCHES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final Clock clock;

    public StockIntegrityService(JdbcClient jdbc, ObjectMapper json, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    /** Runs the check for the current tenant and stores the report. */
    @Transactional
    public Report check() {
        UUID tenant = TenantContext.requireTenant().value();
        UUID triggeredBy = TenantContext.actor() instanceof com.pharmacore.shared.context.Actor.TenantUser user
                ? user.userId() : null;
        List<Mismatch> mismatches = new ArrayList<>(jdbc.sql("""
                        SELECT l.id AS lot_id, l.stock_id, l.lot_number, l.quantity, p.designation, p.cip13,
                               coalesce(m.total, 0) AS computed
                        FROM stock_lot l
                        JOIN stock s ON s.id = l.stock_id
                        JOIN product p ON p.id = s.product_id
                        LEFT JOIN (
                            SELECT lot_id, sum(quantity) AS total
                            FROM stock_movement
                            WHERE tenant_id = :tenant AND lot_id IS NOT NULL
                            GROUP BY lot_id
                        ) m ON m.lot_id = l.id
                        WHERE l.tenant_id = :tenant AND l.quantity <> coalesce(m.total, 0)
                        ORDER BY p.designation, l.lot_number""")
                .param("tenant", tenant)
                .query((rs, rowNum) -> new Mismatch(MismatchKind.LOT_QUANTITY,
                        rs.getObject("stock_id", UUID.class), rs.getObject("lot_id", UUID.class),
                        rs.getString("designation"), rs.getString("cip13"), rs.getString("lot_number"),
                        rs.getInt("quantity"), rs.getInt("computed")))
                .list());
        mismatches.addAll(jdbc.sql("""
                        SELECT s.id AS stock_id, s.reserved_quantity, p.designation, p.cip13,
                               coalesce(m.total, 0) AS computed
                        FROM stock s
                        JOIN product p ON p.id = s.product_id
                        LEFT JOIN (
                            SELECT stock_id, sum(quantity) AS total
                            FROM stock_movement
                            WHERE tenant_id = :tenant AND movement_type IN ('RESERVATION', 'RESERVATION_RELEASE')
                            GROUP BY stock_id
                        ) m ON m.stock_id = s.id
                        WHERE s.tenant_id = :tenant AND s.reserved_quantity <> coalesce(m.total, 0)
                        ORDER BY p.designation""")
                .param("tenant", tenant)
                .query((rs, rowNum) -> new Mismatch(MismatchKind.RESERVED_QUANTITY,
                        rs.getObject("stock_id", UUID.class), null, rs.getString("designation"),
                        rs.getString("cip13"), null, rs.getInt("reserved_quantity"), rs.getInt("computed")))
                .list());
        int lotsChecked = jdbc.sql("SELECT count(*) FROM stock_lot WHERE tenant_id = :tenant")
                .param("tenant", tenant).query(Integer.class).single();
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO stock_integrity_report (id, tenant_id, run_at, triggered_by, lots_checked, mismatches, details)
                        VALUES (:id, :tenant, :runAt, :by, :lots, :count, :details::jsonb)""")
                .param("id", id)
                .param("tenant", tenant)
                .param("runAt", Timestamp.from(now))
                .param("by", triggeredBy)
                .param("lots", lotsChecked)
                .param("count", mismatches.size())
                .param("details", write(mismatches))
                .update();
        if (!mismatches.isEmpty()) {
            audit.record("STOCK_INTEGRITY_MISMATCH", "STOCK_INTEGRITY_REPORT", id, Map.of(
                    "lotsChecked", Integer.toString(lotsChecked),
                    "mismatches", Integer.toString(mismatches.size()),
                    "firstProduct", mismatches.getFirst().designation()));
        } else {
            audit.record("STOCK_INTEGRITY_OK", "STOCK_INTEGRITY_REPORT", id, Map.of(
                    "lotsChecked", Integer.toString(lotsChecked)));
        }
        return new Report(id, now, lotsChecked, mismatches.size(), mismatches);
    }

    @Transactional(readOnly = true)
    public List<Report> recent(int limit) {
        return jdbc.sql("""
                        SELECT id, run_at, lots_checked, mismatches, details::text AS details
                        FROM stock_integrity_report
                        ORDER BY run_at DESC
                        LIMIT :limit""")
                .param("limit", Math.clamp(limit, 1, 100))
                .query((rs, rowNum) -> new Report(rs.getObject("id", UUID.class), rs.getTimestamp("run_at").toInstant(),
                        rs.getInt("lots_checked"), rs.getInt("mismatches"), read(rs.getString("details"))))
                .list();
    }

    private String write(List<Mismatch> mismatches) {
        try {
            return json.writeValueAsString(mismatches);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize integrity report", e);
        }
    }

    private List<Mismatch> read(String details) {
        try {
            return json.readValue(details, MISMATCHES);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt integrity report", e);
        }
    }
}
