package com.pharmacore.reporting.application;

import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Management dashboard of one pharmacy: sales, margin, payment mix, best sellers, stock value
 * and expiry exposure, receivables and cash differences over a period, with the previous period
 * of the same length for comparison. Every figure comes from one explicit aggregate query so
 * the page costs a handful of index scans whatever the volume.
 */
@Service
public class DashboardService {

    private final JdbcClient jdbc;
    private final TenantRepository tenants;

    public DashboardService(JdbcClient jdbc, TenantRepository tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    public record SalesKpis(long count, long netCents, long averageBasketCents, long marginCents,
                            long previousCount, long previousNetCents, long cancelledCount) {
    }

    public record DayPoint(LocalDate date, long netCents, long count) {
    }

    public record PaymentShare(String method, String provider, long amountCents) {
    }

    public record TopProduct(UUID productId, String cip13, String designation, long quantity, long netCents) {
    }

    public record StockKpis(long valueAtCostCents, long expiringWithin90DaysCents, long expiringLots,
                            long belowThreshold, long shortfallProducts) {
    }

    public record ReceivableKpis(long customerCreditCents, long debtors) {
    }

    public record CashKpis(long sessionsClosed, long differenceCents, long expensesCents) {
    }

    public record Dashboard(Period period, SalesKpis sales, List<DayPoint> daily, List<PaymentShare> payments,
                            List<TopProduct> topProducts, StockKpis stock, ReceivableKpis receivables, CashKpis cash) {
    }

    @Transactional(readOnly = true)
    public Dashboard build(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Period end precedes its start");
        }
        UUID tenant = TenantContext.requireTenant().value();
        ZoneId zone = tenants.findById(tenant).map(t -> ZoneId.of(t.timeZone())).orElse(ZoneId.of("UTC"));
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        Instant previousStart = from.minusDays(days).atStartOfDay(zone).toInstant();

        SalesKpis sales = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at >= :start) AS sales,
                               coalesce(sum(s.total_net_cents) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at >= :start), 0) AS net,
                               count(*) FILTER (WHERE s.status = 'CANCELLED' AND s.occurred_at >= :start) AS cancelled,
                               count(*) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at < :start) AS prev_sales,
                               coalesce(sum(s.total_net_cents) FILTER (WHERE s.status = 'COMPLETED' AND s.occurred_at < :start), 0) AS prev_net
                        FROM sale s
                        WHERE s.tenant_id = :tenant AND s.occurred_at >= :prevStart AND s.occurred_at < :end""")
                .param("tenant", tenant)
                .param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .param("prevStart", Timestamp.from(previousStart))
                .query((rs, rowNum) -> new SalesKpis(rs.getLong("sales"), rs.getLong("net"), 0, 0,
                        rs.getLong("prev_sales"), rs.getLong("prev_net"), rs.getLong("cancelled")))
                .single();
        long cost = jdbc.sql("""
                        SELECT coalesce(sum(a.quantity * sl.unit_cost_cents), 0)
                        FROM sale s
                        JOIN sale_line l ON l.sale_id = s.id
                        JOIN sale_line_allocation a ON a.sale_line_id = l.id
                        JOIN stock_lot sl ON sl.id = a.lot_id
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :start AND s.occurred_at < :end""")
                .param("tenant", tenant).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query(Long.class).single();
        sales = new SalesKpis(sales.count(), sales.netCents(),
                sales.count() == 0 ? 0 : sales.netCents() / sales.count(), sales.netCents() - cost,
                sales.previousCount(), sales.previousNetCents(), sales.cancelledCount());

        List<DayPoint> daily = jdbc.sql("""
                        SELECT (s.occurred_at AT TIME ZONE :zone)::date AS day, sum(s.total_net_cents) AS net, count(*) AS sales
                        FROM sale s
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :start AND s.occurred_at < :end
                        GROUP BY day ORDER BY day""")
                .param("tenant", tenant).param("zone", zone.getId())
                .param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, rowNum) -> new DayPoint(rs.getDate("day").toLocalDate(), rs.getLong("net"), rs.getLong("sales")))
                .list();

        List<PaymentShare> payments = jdbc.sql("""
                        SELECT p.method, p.provider, sum(p.amount_cents) AS amount
                        FROM sale_payment p JOIN sale s ON s.id = p.sale_id
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :start AND s.occurred_at < :end
                        GROUP BY p.method, p.provider ORDER BY amount DESC""")
                .param("tenant", tenant).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, rowNum) -> new PaymentShare(rs.getString("method"), rs.getString("provider"), rs.getLong("amount")))
                .list();

        List<TopProduct> top = jdbc.sql("""
                        SELECT l.product_id, p.cip13, p.designation, sum(l.quantity) AS quantity, sum(l.net_cents) AS net
                        FROM sale_line l
                        JOIN sale s ON s.id = l.sale_id
                        JOIN product p ON p.id = l.product_id
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :start AND s.occurred_at < :end
                        GROUP BY l.product_id, p.cip13, p.designation
                        ORDER BY net DESC, quantity DESC
                        LIMIT 10""")
                .param("tenant", tenant).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, rowNum) -> new TopProduct(rs.getObject("product_id", UUID.class), rs.getString("cip13"),
                        rs.getString("designation"), rs.getLong("quantity"), rs.getLong("net")))
                .list();

        StockKpis stock = jdbc.sql("""
                        WITH lots AS (
                            SELECT sl.quantity, sl.unit_cost_cents, sl.expiry_date, st.id AS stock_id
                            FROM stock_lot sl JOIN stock st ON st.id = sl.stock_id
                            WHERE st.tenant_id = :tenant AND sl.quantity > 0
                        ),
                        onhand AS (
                            SELECT st.id, st.reorder_threshold, st.shortfall_quantity,
                                   coalesce((SELECT sum(l.quantity) FROM lots l WHERE l.stock_id = st.id AND l.expiry_date > :today), 0) AS available
                            FROM stock st WHERE st.tenant_id = :tenant
                        )
                        SELECT (SELECT coalesce(sum(quantity * unit_cost_cents), 0) FROM lots WHERE expiry_date > :today) AS value_cost,
                               (SELECT coalesce(sum(quantity * unit_cost_cents), 0) FROM lots
                                 WHERE expiry_date > :today AND expiry_date <= :today::date + 90) AS expiring_value,
                               (SELECT count(*) FROM lots WHERE expiry_date > :today AND expiry_date <= :today::date + 90) AS expiring_lots,
                               (SELECT count(*) FROM onhand WHERE reorder_threshold > 0 AND available < reorder_threshold) AS below,
                               (SELECT count(*) FROM onhand WHERE shortfall_quantity > 0) AS shortfall""")
                .param("tenant", tenant).param("today", java.sql.Date.valueOf(LocalDate.now(zone)))
                .query((rs, rowNum) -> new StockKpis(rs.getLong("value_cost"), rs.getLong("expiring_value"),
                        rs.getLong("expiring_lots"), rs.getLong("below"), rs.getLong("shortfall")))
                .single();

        ReceivableKpis receivables = jdbc.sql("""
                        SELECT coalesce(sum(credit_balance_cents) FILTER (WHERE credit_balance_cents > 0), 0) AS credit,
                               count(*) FILTER (WHERE credit_balance_cents > 0) AS debtors
                        FROM customer WHERE tenant_id = :tenant AND active""")
                .param("tenant", tenant)
                .query((rs, rowNum) -> new ReceivableKpis(rs.getLong("credit"), rs.getLong("debtors")))
                .single();

        CashKpis cash = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE cs.status = 'CLOSED') AS closed,
                               coalesce(sum(cs.difference_cents) FILTER (WHERE cs.status = 'CLOSED'), 0) AS difference,
                               coalesce((SELECT sum(m.amount_cents) FROM cash_movement m
                                         WHERE m.tenant_id = :tenant AND m.kind = 'EXPENSE'
                                           AND m.occurred_at >= :start AND m.occurred_at < :end), 0) AS expenses
                        FROM cash_session cs
                        WHERE cs.tenant_id = :tenant AND cs.opened_at >= :start AND cs.opened_at < :end""")
                .param("tenant", tenant).param("start", Timestamp.from(start)).param("end", Timestamp.from(end))
                .query((rs, rowNum) -> new CashKpis(rs.getLong("closed"), rs.getLong("difference"), rs.getLong("expenses")))
                .single();

        return new Dashboard(new Period(from, to), sales, daily, payments, top, stock, receivables, cash);
    }
}
