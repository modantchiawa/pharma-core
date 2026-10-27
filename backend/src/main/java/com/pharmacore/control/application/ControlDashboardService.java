package com.pharmacore.control.application;

import com.pharmacore.control.domain.ActivityHours;
import com.pharmacore.control.domain.Amounts;
import com.pharmacore.control.domain.AnomalyRules;
import com.pharmacore.control.domain.AnomalyRules.Metric;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleFunction;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Contrôle interne" dashboard: per employee over a period, the figures through which
 * internal fraud shows (cancellations after payment, discounts, approvals asked, stock exits,
 * returns, cash variances, count variances during their presence, activity outside hours, sales
 * without stock), each with an explained flag, the drill-down to the underlying events, and the
 * narcotics register. One aggregate query per metric, never one per employee.
 */
@Service
public class ControlDashboardService {

    static final int MAX_DAYS = 366;

    /** One employee's figures; amounts in minor units of the pharmacy currency. */
    public record EmployeeMetrics(UUID userId, String displayName, boolean active, int sales, long salesNetCents,
                                  int cancellations, long cancellationsCents, int cancellationsAfterPayment,
                                  double cancellationRate, long discountsCents, int supervisorOverrides,
                                  int adjustments, long adjustmentsValueCents, int writeOffs,
                                  long writeOffsValueCents, int returns, long returnsCents, int cashSessions,
                                  long cashVarianceCents, long worstCashVarianceCents, int countDiscrepancies,
                                  int outsideHours, int shortfallSales, List<Flag> flags) {
    }

    /** A metric's anomaly flag with the sentence that justifies it ("pourquoi ce signalement"). */
    public record Flag(Metric metric, boolean flagged, double value, double teamMedian, String explanation) {
    }

    public record Overview(LocalDate from, LocalDate to, String currency, List<EmployeeMetrics> employees,
                           int flaggedEmployees, int openAlerts) {
    }

    /** One underlying event of a metric (sale, movement, session, audit entry). */
    public record Event(Instant at, String type, String label, String detail, Long amountCents, String reference) {
    }

    private final ControlSettingsService settings;
    private final TenantRepository tenants;
    private final JdbcClient jdbc;

    public ControlDashboardService(ControlSettingsService settings, TenantRepository tenants, JdbcClient jdbc) {
        this.settings = settings;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to) {
        Period period = period(from, to);
        var effective = settings.effective();
        String currency = currency();
        Map<UUID, Builder> staff = new LinkedHashMap<>();
        jdbc.sql("SELECT id, display_name, active FROM app_user ORDER BY display_name")
                .query((RowCallbackHandler) rs -> staff.put(rs.getObject("id", UUID.class),
                        new Builder(rs.getString("display_name"), rs.getBoolean("active"))));
        var params = period.params();

        query("""
                SELECT cashier_user_id AS user_id, count(*) AS n, coalesce(sum(total_net_cents), 0) AS amount,
                       coalesce(sum(total_discount_cents) FILTER (WHERE status = 'COMPLETED'), 0) AS discounts,
                       count(*) FILTER (WHERE anomalies LIKE '%STOCK_SHORTFALL%') AS shortfalls
                FROM sale WHERE occurred_at >= :from AND occurred_at < :to
                GROUP BY cashier_user_id""", params, staff, (b, rs) -> {
            b.sales = rs.getInt("n");
            b.salesNet = rs.getLong("amount");
            b.discounts = rs.getLong("discounts");
            b.shortfalls = rs.getInt("shortfalls");
        });
        query("""
                SELECT s.cancelled_by AS user_id, count(*) AS n, coalesce(sum(s.total_net_cents), 0) AS amount,
                       count(*) FILTER (WHERE paid.cents > 0) AS after_payment,
                       count(*) FILTER (WHERE s.approved_by IS NOT NULL AND s.approved_by <> s.cancelled_by) AS approved
                FROM sale s
                LEFT JOIN LATERAL (
                    SELECT coalesce(sum(p.amount_cents), 0) AS cents FROM sale_payment p
                    WHERE p.sale_id = s.id AND p.method NOT IN ('CREDIT', 'THIRD_PARTY_PAYER')
                ) paid ON true
                WHERE s.status = 'CANCELLED' AND s.cancelled_at >= :from AND s.cancelled_at < :to
                GROUP BY s.cancelled_by""", params, staff, (b, rs) -> {
            b.cancellations = rs.getInt("n");
            b.cancellationsAmount = rs.getLong("amount");
            b.afterPayment = rs.getInt("after_payment");
            b.overrides += rs.getInt("approved");
        });
        query("""
                SELECT requested_by AS user_id, count(*) AS n FROM control_approval
                WHERE approved_at >= :from AND approved_at < :to AND action <> 'SALE_CANCELLATION'
                GROUP BY requested_by""", params, staff, (b, rs) -> b.overrides += rs.getInt("n"));
        query("""
                SELECT m.recorded_by AS user_id, count(*) AS n, coalesce(sum(m.quantity * l.unit_cost_cents), 0) AS value
                FROM stock_movement m JOIN stock_lot l ON l.id = m.lot_id
                WHERE m.movement_type = 'ADJUSTMENT' AND m.occurred_at >= :from AND m.occurred_at < :to
                GROUP BY m.recorded_by""", params, staff, (b, rs) -> {
            b.adjustments = rs.getInt("n");
            b.adjustmentsValue = rs.getLong("value");
        });
        query("""
                SELECT recorded_by AS user_id, count(*) AS n, coalesce(sum(value_cents), 0) AS value
                FROM stock_write_off WHERE occurred_at >= :from AND occurred_at < :to
                GROUP BY recorded_by""", params, staff, (b, rs) -> {
            b.writeOffs = rs.getInt("n");
            b.writeOffsValue = rs.getLong("value");
        });
        query("""
                SELECT recorded_by AS user_id, count(*) AS n, coalesce(sum(refund_cents), 0) AS amount
                FROM sale_return WHERE occurred_at >= :from AND occurred_at < :to
                GROUP BY recorded_by""", params, staff, (b, rs) -> {
            b.returns = rs.getInt("n");
            b.returnsAmount = rs.getLong("amount");
        });
        query("""
                SELECT coalesce(closed_by, opened_by) AS user_id, count(*) AS n,
                       coalesce(sum(abs(difference_cents)), 0) AS total,
                       coalesce(min(difference_cents) FILTER (WHERE difference_cents < 0), 0) AS worst_short,
                       coalesce(max(difference_cents) FILTER (WHERE difference_cents > 0), 0) AS worst_over
                FROM cash_session WHERE status = 'CLOSED' AND closed_at >= :from AND closed_at < :to
                GROUP BY coalesce(closed_by, opened_by)""", params, staff, (b, rs) -> {
            b.cashSessions = rs.getInt("n");
            b.cashVariance = rs.getLong("total");
            long shortage = rs.getLong("worst_short");
            long excess = rs.getLong("worst_over");
            b.worstCashVariance = Math.abs(shortage) >= excess ? shortage : excess;
        });
        query("""
                WITH disc AS (
                    SELECT c.id AS count_id, l.product_id, c.started_at,
                           coalesce((SELECT max(pc.validated_at) FROM stock_count pc
                                     JOIN stock_count_line pl ON pl.count_id = pc.id
                                     WHERE pc.status = 'VALIDATED' AND pc.id <> c.id AND pl.product_id = l.product_id
                                       AND pl.counted_quantity IS NOT NULL AND pc.validated_at < c.started_at),
                                    c.started_at - interval '30 days') AS window_from
                    FROM control_daily_count d
                    JOIN stock_count c ON c.id = d.stock_count_id
                    JOIN stock_count_line l ON l.count_id = c.id
                    WHERE c.status = 'VALIDATED' AND c.validated_at >= :from AND c.validated_at < :to
                      AND l.counted_quantity IS NOT NULL
                    GROUP BY c.id, l.product_id, c.started_at
                    HAVING sum(l.counted_quantity) <> sum(l.expected_quantity)
                )
                SELECT duty.user_id, count(*) AS n
                FROM disc
                JOIN LATERAL (
                    SELECT cs.opened_by AS user_id FROM cash_session cs
                    WHERE cs.opened_at < disc.started_at
                      AND coalesce(cs.closed_at, 'infinity'::timestamptz) > disc.window_from
                    UNION
                    SELECT s.cashier_user_id FROM sale s
                    WHERE s.occurred_at > disc.window_from AND s.occurred_at <= disc.started_at
                ) duty ON true
                GROUP BY duty.user_id""", params, staff, (b, rs) -> b.countDiscrepancies = rs.getInt("n"));
        outsideHours(period, effective.hours(), staff);

        List<Builder> builders = new ArrayList<>(staff.values());
        List<UUID> ids = new ArrayList<>(staff.keySet());
        Map<Metric, List<Double>> team = new EnumMap<>(Metric.class);
        for (Metric metric : Metric.values()) {
            team.put(metric, builders.stream().filter(Builder::activeInPeriod).map(b -> b.value(metric)).toList());
        }
        DoubleFunction<String> money = value -> Amounts.format(Math.round(value), currency);
        List<EmployeeMetrics> employees = new ArrayList<>();
        for (int i = 0; i < builders.size(); i++) {
            Builder b = builders.get(i);
            if (!b.active && !b.activeInPeriod()) {
                continue;
            }
            List<Flag> flags = new ArrayList<>();
            for (Metric metric : Metric.values()) {
                var rule = AnomalyRules.defaultRule(metric, effective.values().alertCashVarianceCents());
                double median = AnomalyRules.median(team.get(metric));
                double value = b.value(metric);
                var flag = AnomalyRules.evaluate(rule, value, median, format(metric, money));
                flags.add(new Flag(metric, flag.flagged(), value, median, flag.explanation()));
            }
            employees.add(new EmployeeMetrics(ids.get(i), b.name, b.active, b.sales, b.salesNet, b.cancellations,
                    b.cancellationsAmount, b.afterPayment, b.value(Metric.CANCELLATION_RATE), b.discounts,
                    b.overrides, b.adjustments, b.adjustmentsValue, b.writeOffs, b.writeOffsValue, b.returns,
                    b.returnsAmount, b.cashSessions, b.cashVariance, b.worstCashVariance, b.countDiscrepancies,
                    b.outsideHours, b.shortfalls, flags));
        }
        employees.sort((a, c) -> Long.compare(c.flags().stream().filter(Flag::flagged).count(),
                a.flags().stream().filter(Flag::flagged).count()));
        int flagged = (int) employees.stream().filter(e -> e.flags().stream().anyMatch(Flag::flagged)).count();
        int openAlerts = jdbc.sql("SELECT count(*) FROM control_alert WHERE acknowledged_at IS NULL")
                .query(Integer.class).single();
        return new Overview(period.fromDate(), period.toDate(), currency, employees, flagged, openAlerts);
    }

    /** The events behind one metric of one employee. */
    @Transactional(readOnly = true)
    public List<Event> events(UUID userId, Metric metric, LocalDate from, LocalDate to) {
        Period period = period(from, to);
        Map<String, Object> params = new LinkedHashMap<>(period.params());
        params.putAll(hoursParams(settings.effective().hours()));
        params.put("user", userId);
        String sql = switch (metric) {
            case CANCELLATIONS_AFTER_PAYMENT, CANCELLATION_RATE -> """
                    SELECT s.cancelled_at AS at, 'CANCELLATION' AS type, 'Ticket ' || s.ticket_number AS label,
                           'Annulée ' || CASE
                                   WHEN s.cancelled_at - s.occurred_at < interval '1 hour'
                                       THEN floor(extract(epoch FROM s.cancelled_at - s.occurred_at) / 60) || ' min'
                                   WHEN s.cancelled_at - s.occurred_at < interval '2 days'
                                       THEN floor(extract(epoch FROM s.cancelled_at - s.occurred_at) / 3600) || ' h'
                                   ELSE floor(extract(epoch FROM s.cancelled_at - s.occurred_at) / 86400) || ' j'
                               END || ' après la vente'
                               || ' · motif : ' || coalesce(s.cancel_reason, '—')
                               || CASE WHEN paid.cents > 0 THEN ' · encaissé' ELSE ' · non encaissé' END
                               || CASE WHEN ap.display_name IS NOT NULL THEN ' · approuvé par ' || ap.display_name
                                       ELSE '' END AS detail,
                           s.total_net_cents AS amount, s.id::text AS reference
                    FROM sale s
                    LEFT JOIN app_user ap ON ap.id = s.approved_by
                    LEFT JOIN LATERAL (
                        SELECT coalesce(sum(p.amount_cents), 0) AS cents FROM sale_payment p
                        WHERE p.sale_id = s.id AND p.method NOT IN ('CREDIT', 'THIRD_PARTY_PAYER')
                    ) paid ON true
                    WHERE s.cancelled_by = :user AND s.status = 'CANCELLED'
                      AND s.cancelled_at >= :from AND s.cancelled_at < :to
                    ORDER BY s.cancelled_at DESC LIMIT 500""";
            case DISCOUNTS -> """
                    SELECT s.occurred_at AS at, 'SALE' AS type, 'Ticket ' || s.ticket_number AS label,
                           'Remise sur ' || s.total_gross_cents || ' brut' AS detail,
                           s.total_discount_cents AS amount, s.id::text AS reference
                    FROM sale s
                    WHERE s.cashier_user_id = :user AND s.total_discount_cents > 0 AND s.status = 'COMPLETED'
                      AND s.occurred_at >= :from AND s.occurred_at < :to
                    ORDER BY s.occurred_at DESC LIMIT 500""";
            case SUPERVISOR_OVERRIDES -> """
                    SELECT a.approved_at AS at, 'APPROVAL' AS type,
                           CASE a.action WHEN 'STOCK_ADJUSTMENT' THEN 'Ajustement de stock'
                                         WHEN 'STOCK_WRITE_OFF' THEN 'Sortie de stock'
                                         WHEN 'STOCK_COUNT' THEN 'Validation d''inventaire'
                                         WHEN 'SALE_RETURN' THEN 'Retour client'
                                         ELSE 'Annulation de vente' END AS label,
                           'Seconde validation : ' || u.display_name || ' · motif : ' || a.reason
                               || CASE WHEN a.narcotic THEN ' · stupéfiant' ELSE '' END AS detail,
                           a.amount_cents AS amount, a.resource_id::text AS reference
                    FROM control_approval a JOIN app_user u ON u.id = a.approved_by
                    WHERE a.requested_by = :user AND a.approved_at >= :from AND a.approved_at < :to
                    UNION ALL
                    SELECT s.cancelled_at, 'APPROVAL', 'Annulation du ticket ' || s.ticket_number,
                           'Approuvée par ' || u.display_name, s.total_net_cents, s.id::text
                    FROM sale s JOIN app_user u ON u.id = s.approved_by
                    WHERE s.cancelled_by = :user AND s.approved_by <> s.cancelled_by
                      AND s.cancelled_at >= :from AND s.cancelled_at < :to
                      AND NOT EXISTS (SELECT 1 FROM control_approval ca
                                      WHERE ca.resource_id = s.id AND ca.action = 'SALE_CANCELLATION')
                    ORDER BY 1 DESC LIMIT 500""";
            case STOCK_LOSSES -> """
                    SELECT m.occurred_at AS at, 'ADJUSTMENT' AS type, p.designation AS label,
                           'Lot ' || l.lot_number || ' · ' || CASE WHEN m.quantity > 0 THEN '+' ELSE '' END
                               || m.quantity || ' unité(s) (correction)' AS detail,
                           (m.quantity * l.unit_cost_cents)::bigint AS amount, m.lot_id::text AS reference
                    FROM stock_movement m JOIN stock_lot l ON l.id = m.lot_id
                    JOIN stock s ON s.id = m.stock_id JOIN product p ON p.id = s.product_id
                    WHERE m.recorded_by = :user AND m.movement_type = 'ADJUSTMENT'
                      AND m.occurred_at >= :from AND m.occurred_at < :to
                    UNION ALL
                    SELECT w.occurred_at, 'WRITE_OFF', p.designation,
                           initcap(lower(w.kind)) || ' · lot ' || l.lot_number || ' · -' || w.quantity
                               || ' unité(s) · motif : ' || w.reason
                               || coalesce(' · seconde validation : ' || ap.display_name, ''),
                           -w.value_cents, w.id::text
                    FROM stock_write_off w JOIN stock_lot l ON l.id = w.lot_id JOIN product p ON p.id = w.product_id
                    LEFT JOIN control_approval a ON a.id = w.approval_id
                    LEFT JOIN app_user ap ON ap.id = a.approved_by
                    WHERE w.recorded_by = :user AND w.occurred_at >= :from AND w.occurred_at < :to
                    ORDER BY 1 DESC LIMIT 500""";
            case RETURNS -> """
                    SELECT r.occurred_at AS at, 'RETURN' AS type, 'Ticket ' || s.ticket_number AS label,
                           'Motif : ' || r.reason || coalesce(' · seconde validation : ' || ap.display_name, '')
                               AS detail,
                           r.refund_cents AS amount, r.sale_id::text AS reference
                    FROM sale_return r JOIN sale s ON s.id = r.sale_id
                    LEFT JOIN control_approval a ON a.id = r.approval_id
                    LEFT JOIN app_user ap ON ap.id = a.approved_by
                    WHERE r.recorded_by = :user AND r.occurred_at >= :from AND r.occurred_at < :to
                    ORDER BY r.occurred_at DESC LIMIT 500""";
            case CASH_VARIANCE -> """
                    SELECT cs.closed_at AS at, 'CASH_SESSION' AS type, 'Poste ' || cs.terminal_id AS label,
                           'Session de ' || greatest(1, round(extract(epoch FROM cs.closed_at - cs.opened_at) / 3600))
                               || ' h, clôture à l''aveugle' || CASE WHEN cs.notes IS NOT NULL
                                   THEN ' · note : ' || cs.notes ELSE '' END AS detail,
                           cs.difference_cents AS amount, cs.id::text AS reference
                    FROM cash_session cs
                    WHERE coalesce(cs.closed_by, cs.opened_by) = :user AND cs.status = 'CLOSED'
                      AND cs.closed_at >= :from AND cs.closed_at < :to
                    ORDER BY cs.closed_at DESC LIMIT 500""";
            case COUNT_DISCREPANCIES -> """
                    SELECT c.validated_at AS at, 'COUNT' AS type, p.designation AS label,
                           c.label || ' · attendu ' || sum(l.expected_quantity) || ', compté '
                               || sum(l.counted_quantity) AS detail,
                           sum((l.counted_quantity - l.expected_quantity) * lot.unit_cost_cents)::bigint AS amount,
                           c.id::text AS reference
                    FROM control_daily_count d
                    JOIN stock_count c ON c.id = d.stock_count_id
                    JOIN stock_count_line l ON l.count_id = c.id
                    JOIN stock_lot lot ON lot.id = l.lot_id
                    JOIN product p ON p.id = l.product_id
                    WHERE c.status = 'VALIDATED' AND c.validated_at >= :from AND c.validated_at < :to
                      AND l.counted_quantity IS NOT NULL
                      AND EXISTS (
                          SELECT 1 FROM cash_session cs
                          WHERE cs.opened_by = :user AND cs.opened_at < c.started_at
                            AND coalesce(cs.closed_at, 'infinity'::timestamptz) > c.started_at - interval '30 days'
                          UNION ALL
                          SELECT 1 FROM sale s
                          WHERE s.cashier_user_id = :user AND s.occurred_at <= c.started_at
                            AND s.occurred_at > coalesce((SELECT max(pc.validated_at) FROM stock_count pc
                                     JOIN stock_count_line pl ON pl.count_id = pc.id
                                     WHERE pc.status = 'VALIDATED' AND pc.id <> c.id AND pl.product_id = l.product_id
                                       AND pl.counted_quantity IS NOT NULL AND pc.validated_at < c.started_at),
                                    c.started_at - interval '30 days'))
                    GROUP BY c.id, c.validated_at, c.label, p.designation, l.product_id
                    HAVING sum(l.counted_quantity) <> sum(l.expected_quantity)
                    ORDER BY c.validated_at DESC LIMIT 500""";
            case OUTSIDE_HOURS -> """
                    SELECT x.at, x.type, x.label, x.detail, x.amount, x.reference FROM (
                        SELECT s.occurred_at AS at, 'SALE' AS type, 'Ticket ' || s.ticket_number AS label,
                               'Vente hors des heures d''activité, hors garde' AS detail,
                               s.total_net_cents AS amount, s.id::text AS reference
                        FROM sale s
                        WHERE s.cashier_user_id = :user AND s.occurred_at >= :from AND s.occurred_at < :to
                        UNION ALL
                        SELECT m.occurred_at, m.movement_type, p.designation,
                               CASE m.movement_type WHEN 'WRITE_OFF' THEN 'Sortie de stock' ELSE 'Ajustement' END
                                   || ' hors des heures d''activité · ' || m.quantity || ' unité(s)',
                               (m.quantity * l.unit_cost_cents)::bigint, m.lot_id::text
                        FROM stock_movement m JOIN stock_lot l ON l.id = m.lot_id
                        JOIN stock st ON st.id = m.stock_id JOIN product p ON p.id = st.product_id
                        WHERE m.recorded_by = :user AND m.movement_type IN ('ADJUSTMENT', 'WRITE_OFF')
                          AND m.occurred_at >= :from AND m.occurred_at < :to
                    ) x
                    WHERE NOT EXISTS (SELECT 1 FROM unnest(:dows::int[], :opens::time[], :closes::time[]) h(dow, open, close)
                                      WHERE h.dow = extract(isodow FROM x.at AT TIME ZONE :zone)
                                        AND (x.at AT TIME ZONE :zone)::time >= h.open
                                        AND (x.at AT TIME ZONE :zone)::time < h.close)
                      AND NOT EXISTS (SELECT 1 FROM guard_schedule g
                                      WHERE g.pharmacy_tenant_id = :tenant AND g.starts_at <= x.at AND g.ends_at > x.at)
                    ORDER BY x.at DESC LIMIT 500""";
            case SHORTFALL_SALES -> """
                    SELECT s.occurred_at AS at, 'SALE' AS type, 'Ticket ' || s.ticket_number AS label,
                           'Vendu sans stock disponible (vente hors ligne rejouée)' AS detail,
                           s.total_net_cents AS amount, s.id::text AS reference
                    FROM sale s
                    WHERE s.cashier_user_id = :user AND s.anomalies LIKE '%STOCK_SHORTFALL%'
                      AND s.occurred_at >= :from AND s.occurred_at < :to
                    ORDER BY s.occurred_at DESC LIMIT 500""";
        };
        var statement = jdbc.sql(sql);
        for (var entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        return statement.query((rs, rowNum) -> new Event(rs.getTimestamp("at").toInstant(), rs.getString("type"),
                        rs.getString("label"), rs.getString("detail"), (Long) rs.getObject("amount", Long.class),
                        rs.getString("reference")))
                .list();
    }

    /** A goods receipt compared with its order (blind or not), as kept by the receipt check. */
    public record ReceiptCheckView(UUID receiptId, String orderNumber, String supplier, Instant receivedAt,
                                   String receivedBy, boolean blind, boolean separationWarning, int discrepancyLines,
                                   int discrepancyUnits, String lines) {
    }

    /** Second signatures given over the period. */
    public record ApprovalView(UUID id, String action, String resourceType, UUID resourceId, long amountCents,
                               boolean narcotic, String requestedBy, String approvedBy, String reason,
                               Instant approvedAt) {
    }

    @Transactional(readOnly = true)
    public List<ReceiptCheckView> receiptChecks(LocalDate from, LocalDate to) {
        Period period = period(from, to);
        return jdbc.sql("""
                        SELECT k.goods_receipt_id, o.number, sp.name AS supplier, k.checked_at, u.display_name,
                               k.blind, k.separation_warning, k.discrepancy_lines, k.discrepancy_units, k.lines::text
                        FROM goods_receipt_check k
                        JOIN purchase_order o ON o.id = k.purchase_order_id
                        JOIN supplier sp ON sp.id = o.supplier_id
                        LEFT JOIN app_user u ON u.id = k.received_by
                        WHERE k.checked_at >= :from AND k.checked_at < :to
                        ORDER BY k.checked_at DESC LIMIT 200""")
                .param("from", period.params().get("from"))
                .param("to", period.params().get("to"))
                .query((rs, rowNum) -> new ReceiptCheckView(rs.getObject("goods_receipt_id", UUID.class),
                        rs.getString("number"), rs.getString("supplier"), rs.getTimestamp("checked_at").toInstant(),
                        rs.getString("display_name"), rs.getBoolean("blind"), rs.getBoolean("separation_warning"),
                        rs.getInt("discrepancy_lines"), rs.getInt("discrepancy_units"), rs.getString("lines")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> approvals(LocalDate from, LocalDate to) {
        Period period = period(from, to);
        return jdbc.sql("""
                        SELECT a.id, a.action, a.resource_type, a.resource_id, a.amount_cents, a.narcotic,
                               r.display_name AS requested_by, v.display_name AS approved_by, a.reason, a.approved_at
                        FROM control_approval a
                        JOIN app_user r ON r.id = a.requested_by
                        JOIN app_user v ON v.id = a.approved_by
                        WHERE a.approved_at >= :from AND a.approved_at < :to
                        ORDER BY a.approved_at DESC LIMIT 300""")
                .param("from", period.params().get("from"))
                .param("to", period.params().get("to"))
                .query((rs, rowNum) -> new ApprovalView(rs.getObject("id", UUID.class), rs.getString("action"),
                        rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                        rs.getLong("amount_cents"), rs.getBoolean("narcotic"), rs.getString("requested_by"),
                        rs.getString("approved_by"), rs.getString("reason"),
                        rs.getTimestamp("approved_at").toInstant()))
                .list();
    }

    /** The audit trail of one employee over the period (latest first). */
    @Transactional(readOnly = true)
    public List<Event> auditTrail(UUID userId, LocalDate from, LocalDate to) {
        Period period = period(from, to);
        return jdbc.sql("""
                        SELECT occurred_at, action, resource_type, resource_id, details
                        FROM audit_event
                        WHERE actor_type = 'USER' AND actor_id = :user AND occurred_at >= :from AND occurred_at < :to
                        ORDER BY occurred_at DESC LIMIT 300""")
                .param("user", userId.toString())
                .param("from", period.params().get("from"))
                .param("to", period.params().get("to"))
                .query((rs, rowNum) -> new Event(rs.getTimestamp("occurred_at").toInstant(), "AUDIT",
                        rs.getString("action"), rs.getString("details"), null,
                        rs.getString("resource_type") + ":" + rs.getString("resource_id")))
                .list();
    }

    // ------------------------------------------------------------------ outside activity hours

    private void outsideHours(Period period, ActivityHours hours, Map<UUID, Builder> staff) {
        Map<String, Object> params = new LinkedHashMap<>(period.params());
        params.putAll(hoursParams(hours));
        query("""
                SELECT x.user_id, count(*) AS n FROM (
                    SELECT s.cashier_user_id AS user_id, s.occurred_at AS at FROM sale s
                    WHERE s.occurred_at >= :from AND s.occurred_at < :to
                    UNION ALL
                    SELECT m.recorded_by, m.occurred_at FROM stock_movement m
                    WHERE m.movement_type IN ('ADJUSTMENT', 'WRITE_OFF') AND m.recorded_by IS NOT NULL
                      AND m.occurred_at >= :from AND m.occurred_at < :to
                ) x
                WHERE NOT EXISTS (SELECT 1 FROM unnest(:dows::int[], :opens::time[], :closes::time[]) h(dow, open, close)
                                  WHERE h.dow = extract(isodow FROM x.at AT TIME ZONE :zone)
                                    AND (x.at AT TIME ZONE :zone)::time >= h.open
                                    AND (x.at AT TIME ZONE :zone)::time < h.close)
                  AND NOT EXISTS (SELECT 1 FROM guard_schedule g
                                  WHERE g.pharmacy_tenant_id = :tenant AND g.starts_at <= x.at AND g.ends_at > x.at)
                GROUP BY x.user_id""", params, staff, (b, rs) -> b.outsideHours = rs.getInt("n"));
    }

    private static Map<String, Object> hoursParams(ActivityHours hours) {
        var lines = hours.lines();
        return Map.of(
                "dows", lines.stream().map(d -> d.day().getValue()).toArray(Integer[]::new),
                "opens", lines.stream().map(d -> d.open().toString()).toArray(String[]::new),
                "closes", lines.stream().map(d -> d.close().toString()).toArray(String[]::new));
    }

    // ------------------------------------------------------------------ helpers

    private interface RowWriter {
        void write(Builder builder, ResultSet rs) throws SQLException;
    }

    private void query(String sql, Map<String, Object> params, Map<UUID, Builder> staff, RowWriter writer) {
        var statement = jdbc.sql(sql);
        for (var entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        statement.query((RowCallbackHandler) rs -> {
            UUID user = rs.getObject("user_id", UUID.class);
            if (user != null) {
                Builder builder = staff.get(user);
                if (builder != null) {
                    writer.write(builder, rs);
                }
            }
        });
    }

    private static DoubleFunction<String> format(Metric metric, DoubleFunction<String> money) {
        return switch (metric) {
            case DISCOUNTS, STOCK_LOSSES, CASH_VARIANCE -> money;
            case CANCELLATION_RATE -> value -> String.format(Locale.FRANCE, "%.1f", value);
            default -> value -> value == Math.rint(value) ? Long.toString((long) value)
                    : String.format(Locale.FRANCE, "%.1f", value);
        };
    }

    private record Period(LocalDate fromDate, LocalDate toDate, Instant from, Instant to, ZoneId zone,
                          UUID tenant) {

        Map<String, Object> params() {
            return Map.of("from", Timestamp.from(from), "to", Timestamp.from(to), "zone", zone.getId(),
                    "tenant", tenant);
        }
    }

    private Period period(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new BusinessRuleException("invalid-range", "Période invalide (un an au plus)");
        }
        UUID tenant = TenantContext.requireTenant().value();
        ZoneId zone = tenants.findById(tenant).map(t -> ZoneId.of(t.timeZone())).orElse(ZoneId.of("UTC"));
        return new Period(from, to, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(),
                zone, tenant);
    }

    private String currency() {
        return tenants.findById(TenantContext.requireTenant().value()).map(t -> t.currency()).orElse("XOF");
    }

    /** Figures of one employee while the aggregates are read. */
    private static final class Builder {

        final String name;
        final boolean active;
        int sales;
        long salesNet;
        long discounts;
        int shortfalls;
        int cancellations;
        long cancellationsAmount;
        int afterPayment;
        int overrides;
        int adjustments;
        long adjustmentsValue;
        int writeOffs;
        long writeOffsValue;
        int returns;
        long returnsAmount;
        int cashSessions;
        long cashVariance;
        long worstCashVariance;
        int countDiscrepancies;
        int outsideHours;

        Builder(String name, boolean active) {
            this.name = name;
            this.active = active;
        }

        boolean activeInPeriod() {
            return sales + cancellations + adjustments + writeOffs + returns + cashSessions + countDiscrepancies
                    + outsideHours + overrides > 0;
        }

        /** Losses count positively: negative adjustments and every write-off. */
        double value(Metric metric) {
            return switch (metric) {
                case CANCELLATIONS_AFTER_PAYMENT -> afterPayment;
                case CANCELLATION_RATE -> sales < 10 ? 0 : 100.0 * cancellations / sales;
                case DISCOUNTS -> discounts;
                case SUPERVISOR_OVERRIDES -> overrides;
                case STOCK_LOSSES -> Math.max(0, -adjustmentsValue) + writeOffsValue;
                case RETURNS -> returns;
                case CASH_VARIANCE -> cashVariance;
                case COUNT_DISCREPANCIES -> countDiscrepancies;
                case OUTSIDE_HOURS -> outsideHours;
                case SHORTFALL_SALES -> shortfalls;
            };
        }
    }
}
