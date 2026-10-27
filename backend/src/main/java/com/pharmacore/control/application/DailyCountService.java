package com.pharmacore.control.application;

import com.pharmacore.control.domain.MicroCountSelection;
import com.pharmacore.control.domain.RiskFactors;
import com.pharmacore.control.persistence.DailyCountEntity;
import com.pharmacore.control.persistence.DailyCountRepository;
import com.pharmacore.inventory.application.StockCountService;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Contrôle du jour": every day a handful of products is recounted blind with the regular count
 * engine. The products are drawn by {@link MicroCountSelection} from the risk factors of the
 * moment, and the draw is frozen at the first request of the day, so reloading never changes it.
 * Once validated, each variance is shown with the staff on duty since the previous count of the
 * product, which narrows a shortage down to a few days and a few people.
 */
@Service
public class DailyCountService {

    /** Bound of the "since the previous count" window for a product never counted before. */
    static final Duration FIRST_WINDOW = Duration.ofDays(30);
    private static final DateTimeFormatter LABEL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Item(int rank, UUID productId, String designation, String cip13, int score, List<String> reasons,
                       String locationCode, String locationName, String locationKind, boolean narcotic) {
    }

    /**
     * @param sincePreviousCount whether the window opens at the product's previous validated count,
     *                           or goes back {@link #FIRST_WINDOW} for a product never counted before
     */
    public record Variance(UUID productId, String designation, int expected, int counted, int variance,
                           long valueCents, boolean sensitive, Instant windowFrom, Instant windowTo,
                           boolean sincePreviousCount, List<StaffOnDuty.Member> staffOnDuty) {
    }

    /**
     * @param status PENDING (drawn, not started), OPEN, VALIDATED or CANCELLED
     * @param variances only once validated, and only for those who may see them
     */
    public record DailyCountView(UUID id, LocalDate date, String status, UUID stockCountId, Instant startedAt,
                                 Instant validatedAt, String validatedBy, int lineCount, int countedLines,
                                 List<Item> items, List<Variance> variances) {
    }

    private final DailyCountRepository dailyCounts;
    private final StockCountService counts;
    private final ControlSettingsService settings;
    private final StaffOnDuty staff;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public DailyCountService(DailyCountRepository dailyCounts, StockCountService counts,
                             ControlSettingsService settings, StaffOnDuty staff, TenantCalendar calendar,
                             AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.dailyCounts = dailyCounts;
        this.counts = counts;
        this.settings = settings;
        this.staff = staff;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Today's control, drawn now if this is the first request of the day. */
    @Transactional
    public DailyCountView today() {
        LocalDate day = calendar.today();
        DailyCountEntity daily = dailyCounts.findByCountDate(day).orElseGet(() -> draw(day));
        return view(daily);
    }

    /** Opens the blind count of today's products (once: later calls return the same count). */
    @Transactional
    public DailyCountView start() {
        LocalDate day = calendar.today();
        if (dailyCounts.findByCountDate(day).isEmpty()) {
            draw(day);
        }
        DailyCountEntity daily = dailyCounts.lockByCountDate(day).orElseThrow();
        if (daily.stockCountId() == null) {
            List<UUID> products = items(List.of(daily.id())).getOrDefault(daily.id(), List.of()).stream()
                    .map(Item::productId).toList();
            if (products.isEmpty()) {
                throw new BusinessRuleException("empty-count", "Aucun produit en stock à contrôler aujourd'hui");
            }
            var count = counts.startForProducts("Contrôle du jour du " + LABEL_DATE.format(day), products);
            daily.started(count.id(), TenantContext.requireTenantUser().userId(), clock.instant());
            audit.record("DAILY_COUNT_STARTED", "STOCK_COUNT", count.id(), Map.of(
                    "date", day.toString(), "products", Integer.toString(products.size())));
        }
        return view(daily);
    }

    /** One day of the control, for the dashboard history (details through {@link #variances}). */
    public record DailySummary(UUID id, LocalDate date, String status, UUID stockCountId, int products,
                               int lineCount, int countedLines, int varianceUnits, long varianceValueCents,
                               boolean sensitiveVariance, String validatedBy, Instant validatedAt) {
    }

    @Transactional(readOnly = true)
    public List<DailySummary> history(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT d.id, d.count_date, d.stock_count_id, c.status, c.validated_at,
                               u.display_name AS validated_by,
                               (SELECT count(*) FROM control_daily_count_item i WHERE i.daily_count_id = d.id) AS products,
                               coalesce(agg.lines, 0) AS lines, coalesce(agg.counted, 0) AS counted,
                               coalesce(agg.units, 0) AS units, coalesce(agg.value, 0) AS value,
                               coalesce(agg.sensitive, false) AS sensitive
                        FROM control_daily_count d
                        LEFT JOIN stock_count c ON c.id = d.stock_count_id
                        LEFT JOIN app_user u ON u.id = c.validated_by
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS lines, count(l.counted_quantity) AS counted,
                                   sum(l.counted_quantity - l.expected_quantity) AS units,
                                   sum((l.counted_quantity - l.expected_quantity) * lot.unit_cost_cents) AS value,
                                   bool_or(l.counted_quantity <> l.expected_quantity
                                           AND (p.listing = 'STUPEFIANT' OR coalesce(loc.kind = 'SAFE', false)))
                                       AS sensitive
                            FROM stock_count_line l
                            JOIN stock_lot lot ON lot.id = l.lot_id
                            JOIN product p ON p.id = l.product_id
                            JOIN stock s ON s.id = l.stock_id
                            LEFT JOIN storage_location loc ON loc.id = s.location_id
                            WHERE l.count_id = d.stock_count_id
                        ) agg ON true
                        WHERE d.count_date BETWEEN :from AND :to
                        ORDER BY d.count_date DESC
                        LIMIT 400""")
                .param("from", Date.valueOf(from))
                .param("to", Date.valueOf(to))
                .query((rs, rowNum) -> {
                    Timestamp validated = rs.getTimestamp("validated_at");
                    String status = rs.getString("status");
                    boolean done = "VALIDATED".equals(status);
                    return new DailySummary(rs.getObject("id", UUID.class), rs.getDate("count_date").toLocalDate(),
                            status == null ? "PENDING" : status, rs.getObject("stock_count_id", UUID.class),
                            rs.getInt("products"), rs.getInt("lines"), rs.getInt("counted"),
                            done ? rs.getInt("units") : 0, done ? rs.getLong("value") : 0,
                            done && rs.getBoolean("sensitive"), rs.getString("validated_by"),
                            validated == null ? null : validated.toInstant());
                })
                .list();
    }

    /** Validated variances of one count with the people on duty since each product's previous count. */
    @Transactional(readOnly = true)
    public List<Variance> variances(UUID stockCountId) {
        record Row(UUID productId, String designation, int expected, int counted, long value, boolean sensitive,
                   Instant startedAt, Instant previous) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT l.product_id, p.designation,
                               sum(l.expected_quantity) AS expected, sum(l.counted_quantity) AS counted,
                               sum((l.counted_quantity - l.expected_quantity) * lot.unit_cost_cents) AS value,
                               bool_or(p.listing = 'STUPEFIANT' OR coalesce(loc.kind = 'SAFE', false)) AS sensitive,
                               c.started_at,
                               (SELECT max(pc.validated_at) FROM stock_count pc
                                JOIN stock_count_line pl ON pl.count_id = pc.id
                                WHERE pc.status = 'VALIDATED' AND pc.id <> c.id AND pl.product_id = l.product_id
                                  AND pl.counted_quantity IS NOT NULL AND pc.validated_at < c.started_at) AS previous
                        FROM stock_count c
                        JOIN stock_count_line l ON l.count_id = c.id
                        JOIN product p ON p.id = l.product_id
                        JOIN stock_lot lot ON lot.id = l.lot_id
                        JOIN stock s ON s.id = l.stock_id
                        LEFT JOIN storage_location loc ON loc.id = s.location_id
                        WHERE c.id = :count AND c.status = 'VALIDATED' AND l.counted_quantity IS NOT NULL
                        GROUP BY l.product_id, p.designation, c.id, c.started_at
                        HAVING sum(l.counted_quantity) <> sum(l.expected_quantity)
                        ORDER BY p.designation""")
                .param("count", stockCountId)
                .query((rs, rowNum) -> {
                    Timestamp previous = rs.getTimestamp("previous");
                    return new Row(rs.getObject("product_id", UUID.class), rs.getString("designation"),
                            rs.getInt("expected"), rs.getInt("counted"), rs.getLong("value"),
                            rs.getBoolean("sensitive"), rs.getTimestamp("started_at").toInstant(),
                            previous == null ? null : previous.toInstant());
                })
                .list();
        return rows.stream().map(r -> {
            Instant from = r.previous() != null ? r.previous() : r.startedAt().minus(FIRST_WINDOW);
            return new Variance(r.productId(), r.designation(), r.expected(), r.counted(), r.counted() - r.expected(),
                    r.value(), r.sensitive(), from, r.startedAt(), r.previous() != null,
                    staff.during(from, r.startedAt(), r.productId()));
        }).toList();
    }

    private DailyCountEntity draw(LocalDate day) {
        var effective = settings.ensure();
        UUID tenant = TenantContext.requireTenant().value();
        List<RiskFactors> candidates = candidates(day);
        var picks = MicroCountSelection.select(candidates, effective.values().microCountSize(),
                MicroCountSelection.seed(effective.selectionSalt(), tenant, day));
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        int inserted = jdbc.sql("""
                        INSERT INTO control_daily_count (id, tenant_id, count_date, selected_at)
                        VALUES (:id, :tenant, :day, :at)
                        ON CONFLICT (tenant_id, count_date) DO NOTHING""")
                .param("id", id).param("tenant", tenant).param("day", Date.valueOf(day))
                .param("at", Timestamp.from(now))
                .update();
        if (inserted == 0) {
            // Another terminal drew the same day a moment ago: its draw is the day's draw
            return dailyCounts.findByCountDate(day).orElseThrow();
        }
        int rank = 1;
        for (var pick : picks) {
            String reasons = String.join(" · ", pick.reasons());
            jdbc.sql("""
                            INSERT INTO control_daily_count_item (id, tenant_id, daily_count_id, product_id, rank, score,
                                                                  reasons)
                            VALUES (:id, :tenant, :daily, :product, :rank, :score, :reasons)""")
                    .param("id", UUID.randomUUID()).param("tenant", tenant).param("daily", id)
                    .param("product", pick.factors().productId()).param("rank", rank++)
                    .param("score", Math.min(100, pick.score()))
                    .param("reasons", reasons.length() <= 300 ? reasons : reasons.substring(0, 300))
                    .update();
        }
        return dailyCounts.findById(id).orElseThrow();
    }

    /** Products on the shelf with what the score needs, in one query. */
    List<RiskFactors> candidates(LocalDate day) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        WITH onhand AS (
                            SELECT s.id AS stock_id, s.product_id, s.location_id,
                                   sum(l.quantity::bigint * l.unit_cost_cents) AS value
                            FROM stock s JOIN stock_lot l ON l.stock_id = s.id
                            WHERE l.quantity > 0
                            GROUP BY s.id, s.product_id, s.location_id
                        ), counted AS (
                            SELECT cl.product_id, max(c.validated_at) AS last_counted,
                                   count(DISTINCT c.id) FILTER (WHERE c.validated_at > :since180
                                       AND cl.counted_quantity <> cl.expected_quantity) AS discrepancies
                            FROM stock_count c JOIN stock_count_line cl ON cl.count_id = c.id
                            WHERE c.status = 'VALIDATED' AND cl.counted_quantity IS NOT NULL
                            GROUP BY cl.product_id
                        ), moved AS (
                            SELECT m.stock_id,
                                   coalesce(-sum(m.quantity) FILTER (WHERE m.movement_type IN ('SALE', 'SALE_SHORTFALL')
                                       AND m.occurred_at > :since28), 0) AS sold,
                                   count(*) FILTER (WHERE m.movement_type IN ('ADJUSTMENT', 'WRITE_OFF')
                                       AND m.occurred_at > :since30) AS adjustments
                            FROM stock_movement m
                            WHERE m.occurred_at > :since28 OR m.occurred_at > :since30
                            GROUP BY m.stock_id
                        )
                        SELECT o.product_id, o.value, p.listing = 'STUPEFIANT' AS narcotic,
                               coalesce(loc.kind = 'SAFE', false) AS safe,
                               coalesce(c.discrepancies, 0) AS discrepancies, c.last_counted,
                               coalesce(m.sold, 0) AS sold, coalesce(m.adjustments, 0) AS adjustments
                        FROM onhand o
                        JOIN product p ON p.id = o.product_id
                        LEFT JOIN storage_location loc ON loc.id = o.location_id
                        LEFT JOIN counted c ON c.product_id = o.product_id
                        LEFT JOIN moved m ON m.stock_id = o.stock_id
                        ORDER BY o.product_id""")
                .param("since180", Timestamp.from(now.minus(180, ChronoUnit.DAYS)))
                .param("since28", Timestamp.from(now.minus(28, ChronoUnit.DAYS)))
                .param("since30", Timestamp.from(now.minus(30, ChronoUnit.DAYS)))
                .query((rs, rowNum) -> {
                    Timestamp last = rs.getTimestamp("last_counted");
                    Integer days = last == null ? null
                            : (int) ChronoUnit.DAYS.between(last.toInstant(), now);
                    return new RiskFactors(rs.getObject("product_id", UUID.class), rs.getLong("value"),
                            rs.getBoolean("narcotic"), rs.getBoolean("safe"), rs.getInt("discrepancies"),
                            rs.getLong("sold") / 28.0, days, rs.getInt("adjustments"));
                })
                .list();
    }

    private DailyCountView view(DailyCountEntity daily) {
        return view(daily, items(List.of(daily.id())).getOrDefault(daily.id(), List.of()));
    }

    private DailyCountView view(DailyCountEntity daily, List<Item> items) {
        if (daily.stockCountId() == null) {
            return new DailyCountView(daily.id(), daily.countDate(), "PENDING", null, null, null, null, 0, 0, items,
                    List.of());
        }
        record CountState(String status, Instant validatedAt, String validatedBy, int lines, int counted) {
        }
        CountState state = jdbc.sql("""
                        SELECT c.status, c.validated_at, u.display_name AS validated_by,
                               (SELECT count(*) FROM stock_count_line l WHERE l.count_id = c.id) AS lines,
                               (SELECT count(*) FROM stock_count_line l
                                WHERE l.count_id = c.id AND l.counted_quantity IS NOT NULL) AS counted
                        FROM stock_count c LEFT JOIN app_user u ON u.id = c.validated_by
                        WHERE c.id = :count""")
                .param("count", daily.stockCountId())
                .query((rs, rowNum) -> {
                    Timestamp validated = rs.getTimestamp("validated_at");
                    return new CountState(rs.getString("status"), validated == null ? null : validated.toInstant(),
                            rs.getString("validated_by"), rs.getInt("lines"), rs.getInt("counted"));
                })
                .single();
        boolean reveal = "VALIDATED".equals(state.status()) && mayReveal();
        return new DailyCountView(daily.id(), daily.countDate(), state.status(), daily.stockCountId(),
                daily.startedAt(), state.validatedAt(), state.validatedBy(), state.lines(), state.counted(), items,
                reveal ? variances(daily.stockCountId()) : List.of());
    }

    private static boolean mayReveal() {
        return TenantContext.actor() instanceof Actor.TenantUser user
                && (user.can("CONTROL_VIEW") || user.can(StockCountService.VALIDATE_PERMISSION));
    }

    private Map<UUID, List<Item>> items(List<UUID> dailyIds) {
        Map<UUID, List<Item>> items = new LinkedHashMap<>();
        if (dailyIds.isEmpty()) {
            return items;
        }
        jdbc.sql("""
                        SELECT i.daily_count_id, i.rank, i.product_id, p.designation, p.cip13, i.score, i.reasons,
                               loc.code AS location_code, loc.name AS location_name, loc.kind AS location_kind,
                               p.listing = 'STUPEFIANT' AS narcotic
                        FROM control_daily_count_item i
                        JOIN product p ON p.id = i.product_id
                        LEFT JOIN stock s ON s.product_id = i.product_id
                        LEFT JOIN storage_location loc ON loc.id = s.location_id
                        WHERE i.daily_count_id = ANY(:ids)
                        ORDER BY i.daily_count_id, i.rank""")
                .param("ids", dailyIds.toArray(UUID[]::new))
                .query((RowCallbackHandler) rs -> items
                        .computeIfAbsent(rs.getObject("daily_count_id", UUID.class), id -> new ArrayList<>())
                        .add(new Item(rs.getInt("rank"), rs.getObject("product_id", UUID.class),
                                rs.getString("designation"), rs.getString("cip13"), rs.getInt("score"),
                                reasons(rs.getString("reasons")), rs.getString("location_code"),
                                rs.getString("location_name"), rs.getString("location_kind"),
                                rs.getBoolean("narcotic"))));
        return items;
    }

    private static List<String> reasons(String text) {
        return text == null || text.isBlank() ? List.of() : Arrays.asList(text.split(" · "));
    }
}
