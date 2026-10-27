package com.pharmacore.control.application;

import com.pharmacore.control.domain.Amounts;
import com.pharmacore.control.persistence.ControlAlertEntity.Draft;
import com.pharmacore.control.persistence.ControlAlertEntity.Kind;
import com.pharmacore.control.persistence.ControlAlertEntity.Severity;
import com.pharmacore.control.persistence.ControlSettingsEntity.Values;
import com.pharmacore.shared.audit.AuditRecord;
import com.pharmacore.shared.audit.AuditRecorded;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Detective controls: every audit event of the pharmacy goes through these rules, in its own
 * transaction, and the ones that matter become alerts for the owner.
 * <ul>
 *   <li>adjustment or declared write-off at or above the alert threshold (value at cost), and any
 *       declared theft;</li>
 *   <li>cancellation of a paid sale at or above the threshold;</li>
 *   <li>cash variance at closing at or above the threshold;</li>
 *   <li>count variance on a product stored in the safe or listed as a narcotic;</li>
 *   <li>sale, adjustment or write-off outside activity hours (guard duty excepted);</li>
 *   <li>repeated wrong passwords or PINs on one account within 15 minutes;</li>
 *   <li>an order received by the person who placed it (separation of duties in warning mode).</li>
 * </ul>
 */
@Component
class ControlAlertRules {

    static final Duration PIN_WINDOW = Duration.ofMinutes(15);
    private static final Set<String> PIN_FAILURES = Set.of("LOGIN_FAILED", "PIN_FAILED", "SUPERVISOR_PIN_FAILED");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", Locale.FRANCE);

    private final AlertService alerts;
    private final ControlSettingsService settings;
    private final OperatingHours hours;
    private final TenantRepository tenants;
    private final JdbcClient jdbc;

    ControlAlertRules(AlertService alerts, ControlSettingsService settings, OperatingHours hours,
                      TenantRepository tenants, JdbcClient jdbc) {
        this.alerts = alerts;
        this.settings = settings;
        this.hours = hours;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(AuditRecorded event) {
        if (TenantContext.current().flatMap(c -> c.actor().tenantId()).isEmpty()) {
            return;
        }
        switch (event.action()) {
            case "STOCK_ADJUSTED" -> adjustment(event);
            case "STOCK_WRITTEN_OFF" -> writeOff(event);
            case "SALE_CANCELLED" -> cancellation(event);
            case "SALE_RECORDED" -> sale(event);
            case "CASH_SESSION_CLOSED" -> cashVariance(event);
            case "STOCK_COUNT_VALIDATED" -> countDiscrepancy(event);
            case "GOODS_RECEIPT_CHECKED" -> receipt(event);
            default -> {
                if (PIN_FAILURES.contains(event.action())) {
                    pinFailures(event);
                }
            }
        }
    }

    private void adjustment(AuditRecorded event) {
        AuditRecord record = event.record();
        long value = parseLong(event.detail("valueCents"));
        UUID staff = userOf(record);
        String product = designation(event.detail("productId"));
        Values v = settings.effective().values();
        if (v.alertAdjustmentCents() > 0 && Math.abs(value) >= v.alertAdjustmentCents()) {
            alerts.raise(new Draft(Kind.LARGE_ADJUSTMENT, Severity.WARNING, "Ajustement de stock important",
                    name(staff) + " a corrigé " + product + " de " + signed(event.detail("delta")) + " unité(s), soit "
                            + money(value) + " au coût. Motif : " + event.detail("reason") + approvedBy(event) + ".",
                    "STOCK_LOT", uuid(record.resourceId()), staff, value, record.occurredAt()));
        }
        outsideHours(record, staff, "Ajustement de stock", product + " corrigé de " + signed(event.detail("delta"))
                + " unité(s)", value);
    }

    private void writeOff(AuditRecorded event) {
        AuditRecord record = event.record();
        long value = parseLong(event.detail("valueCents"));
        UUID staff = userOf(record);
        String product = designation(event.detail("productId"));
        String kind = label(event.detail("kind"));
        Values v = settings.effective().values();
        boolean theft = "VOL".equals(event.detail("kind"));
        if (theft || (v.alertAdjustmentCents() > 0 && value >= v.alertAdjustmentCents())) {
            alerts.raise(new Draft(Kind.LARGE_ADJUSTMENT, theft ? Severity.CRITICAL : Severity.WARNING,
                    "Sortie de stock déclarée : " + kind.toLowerCase(Locale.FRANCE),
                    name(staff) + " a sorti " + event.detail("quantity") + " unité(s) de " + product + " (" + kind
                            + "), soit " + money(value) + " au coût. Motif : " + event.detail("reason")
                            + approvedBy(event) + ".",
                    "STOCK_WRITE_OFF", uuid(event.detail("writeOffId")), staff, value, record.occurredAt()));
        }
        outsideHours(record, staff, "Sortie de stock", event.detail("quantity") + " unité(s) de " + product + " ("
                + kind + ")", value);
    }

    private void cancellation(AuditRecorded event) {
        AuditRecord record = event.record();
        long paid = parseLong(event.detail("paidCents"));
        Values v = settings.effective().values();
        if (v.alertCancellationCents() > 0 && paid >= v.alertCancellationCents()) {
            UUID staff = userOf(record);
            String soldAt = event.detail("occurredAt") == null ? "" : " (vendu le "
                    + WHEN.format(hours.local(Instant.parse(event.detail("occurredAt")))) + ")";
            alerts.raise(new Draft(Kind.CANCELLATION_AFTER_PAYMENT, Severity.WARNING, "Annulation après paiement",
                    name(staff) + " a annulé le ticket " + event.detail("ticket") + soldAt + " : " + money(paid)
                            + " avaient été encaissés. Motif : " + event.detail("reason") + ".",
                    "SALE", uuid(record.resourceId()), staff, paid, record.occurredAt()));
        }
    }

    private void sale(AuditRecorded event) {
        String occurred = event.detail("occurredAt");
        String cashier = event.detail("cashierId");
        if (occurred == null || cashier == null) {
            return;
        }
        Instant at = Instant.parse(occurred);
        if (hours.outside(at)) {
            UUID staff = uuid(cashier);
            long net = parseLong(event.detail("netCents"));
            alerts.raise(new Draft(Kind.OUTSIDE_HOURS, Severity.WARNING, "Vente hors des heures d'activité",
                    "Ticket " + event.detail("ticket") + " de " + money(net) + " vendu par " + name(staff) + " le "
                            + WHEN.format(hours.local(at)) + ", en dehors des heures d'ouverture et hors garde.",
                    "SALE", uuid(event.record().resourceId()), staff, net, at));
        }
    }

    private void cashVariance(AuditRecorded event) {
        AuditRecord record = event.record();
        long difference = parseLong(event.detail("differenceCents"));
        Values v = settings.effective().values();
        if (v.alertCashVarianceCents() > 0 && Math.abs(difference) >= v.alertCashVarianceCents()) {
            UUID staff = userOf(record);
            String terminal = jdbc.sql("SELECT terminal_id FROM cash_session WHERE id = :id")
                    .param("id", uuid(record.resourceId())).query(String.class).optional().orElse("?");
            alerts.raise(new Draft(Kind.CASH_VARIANCE, difference < 0 ? Severity.CRITICAL : Severity.WARNING,
                    difference < 0 ? "Manquant de caisse" : "Excédent de caisse",
                    "Écart de " + money(difference) + " à la clôture du poste " + terminal + " par " + name(staff)
                            + " (compté " + money(parseLong(event.detail("countedCents"))) + ", attendu "
                            + money(parseLong(event.detail("expectedCents"))) + ").",
                    "CASH_SESSION", uuid(record.resourceId()), staff, difference, record.occurredAt()));
        }
    }

    private void countDiscrepancy(AuditRecorded event) {
        AuditRecord record = event.record();
        record Sensitive(String designation, int variance, long valueCents, boolean narcotic, boolean safe) {
        }
        List<Sensitive> lines = jdbc.sql("""
                        SELECT p.designation, sum(l.counted_quantity - l.expected_quantity) AS variance,
                               sum((l.counted_quantity - l.expected_quantity) * lot.unit_cost_cents) AS value,
                               bool_or(p.listing = 'STUPEFIANT') AS narcotic,
                               bool_or(coalesce(loc.kind = 'SAFE', false)) AS safe
                        FROM stock_count_line l
                        JOIN product p ON p.id = l.product_id
                        JOIN stock_lot lot ON lot.id = l.lot_id
                        JOIN stock s ON s.id = l.stock_id
                        LEFT JOIN storage_location loc ON loc.id = s.location_id
                        WHERE l.count_id = :count AND l.counted_quantity IS NOT NULL
                          AND l.counted_quantity <> l.expected_quantity
                          AND (p.listing = 'STUPEFIANT' OR loc.kind = 'SAFE')
                        GROUP BY p.designation
                        ORDER BY p.designation""")
                .param("count", uuid(record.resourceId()))
                .query((rs, rowNum) -> new Sensitive(rs.getString("designation"), rs.getInt("variance"),
                        rs.getLong("value"), rs.getBoolean("narcotic"), rs.getBoolean("safe")))
                .list();
        if (lines.isEmpty()) {
            return;
        }
        UUID staff = userOf(record);
        long value = lines.stream().mapToLong(Sensitive::valueCents).sum();
        StringBuilder detail = new StringBuilder("Inventaire « " + event.detail("label") + " » validé par "
                + name(staff) + " : ");
        for (int i = 0; i < lines.size(); i++) {
            Sensitive line = lines.get(i);
            detail.append(i == 0 ? "" : ", ").append(line.designation()).append(' ')
                    .append(signed(Integer.toString(line.variance()))).append(" unité(s)")
                    .append(line.narcotic() ? " (stupéfiant)" : " (coffre)");
        }
        detail.append(", soit ").append(money(value)).append(" au coût.");
        alerts.raise(new Draft(Kind.COUNT_DISCREPANCY, Severity.CRITICAL, "Écart d'inventaire sur un produit sensible",
                detail.toString(), "STOCK_COUNT", uuid(record.resourceId()), staff, value, record.occurredAt()));
    }

    private void receipt(AuditRecorded event) {
        if (!Boolean.parseBoolean(event.detail("separationWarning"))) {
            return;
        }
        AuditRecord record = event.record();
        UUID staff = userOf(record);
        alerts.raise(new Draft(Kind.RECEIPT_DISCREPANCY, Severity.WARNING, "Commande réceptionnée par son acheteur",
                name(staff) + " a réceptionné la commande " + event.detail("order")
                        + " qu'il ou elle avait créée ou envoyée ; " + event.detail("discrepancyLines")
                        + " ligne(s) diffèrent de la commande.",
                "GOODS_RECEIPT", uuid(record.resourceId()), staff, null, record.occurredAt()));
    }

    private void pinFailures(AuditRecorded event) {
        AuditRecord record = event.record();
        UUID account = uuid(record.resourceId());
        if (account == null) {
            return;
        }
        int threshold = settings.effective().values().pinFailureAlertCount();
        int failures = jdbc.sql("""
                        SELECT count(*) FROM audit_event
                        WHERE resource_type = 'USER' AND resource_id = :user
                          AND action IN ('LOGIN_FAILED', 'PIN_FAILED', 'SUPERVISOR_PIN_FAILED')
                          AND occurred_at > :since""")
                .param("user", account.toString())
                .param("since", Timestamp.from(record.occurredAt().minus(PIN_WINDOW)))
                .query(Integer.class).single();
        if (failures != threshold) {
            return;
        }
        String where = event.detail("terminal") == null || "approval".equals(event.detail("terminal"))
                ? "en seconde validation" : "sur le poste " + event.detail("terminal");
        alerts.raise(new Draft(Kind.PIN_FAILURES, Severity.CRITICAL, "Échecs répétés de code PIN",
                failures + " mots de passe ou codes PIN erronés en " + PIN_WINDOW.toMinutes()
                        + " minutes pour le compte de " + name(account) + " (" + where + ").",
                "USER", account, account, null, record.occurredAt()));
    }

    private void outsideHours(AuditRecord record, UUID staff, String what, String detail, long value) {
        if (hours.outside(record.occurredAt())) {
            alerts.raise(new Draft(Kind.OUTSIDE_HOURS, Severity.WARNING, what + " hors des heures d'activité",
                    detail + " par " + name(staff) + " le " + WHEN.format(hours.local(record.occurredAt()))
                            + ", en dehors des heures d'ouverture et hors garde.",
                    record.resourceType(), uuid(record.resourceId()), staff, value, record.occurredAt()));
        }
    }

    private String approvedBy(AuditRecorded event) {
        String approver = event.detail("approvedBy");
        return approver == null || approver.isBlank() ? "" : " · seconde validation : " + name(uuid(approver));
    }

    private String designation(String productId) {
        UUID id = uuid(productId);
        return id == null ? "un produit" : jdbc.sql("SELECT designation FROM product WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse("un produit");
    }

    private String name(UUID userId) {
        if (userId == null) {
            return "Un utilisateur";
        }
        String name = alerts.displayName(userId);
        return name == null ? "Un utilisateur" : name;
    }

    private String money(long minorUnits) {
        String currency = tenants.findById(TenantContext.requireTenant().value()).map(t -> t.currency()).orElse("XOF");
        return Amounts.format(minorUnits, currency);
    }

    private static UUID userOf(AuditRecord record) {
        return "USER".equals(record.actorType()) ? uuid(record.actorId()) : null;
    }

    private static String label(String kind) {
        return switch (kind == null ? "" : kind) {
            case "CASSE" -> "Casse";
            case "PERIME" -> "Périmé";
            case "PERTE" -> "Perte";
            case "VOL" -> "Vol";
            default -> "Sortie";
        };
    }

    private static String signed(String value) {
        long n = parseLong(value);
        return n > 0 ? "+" + n : Long.toString(n);
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null || value.isBlank() || "null".equals(value) ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
