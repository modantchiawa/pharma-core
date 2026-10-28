package com.pharmacore.safety.application;

import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.control.application.AlertService;
import com.pharmacore.control.persistence.ControlAlertEntity;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.safety.domain.DispensedItem;
import com.pharmacore.safety.domain.PatientProfile;
import com.pharmacore.safety.domain.RecentDispensing;
import com.pharmacore.safety.domain.SafetyAlert;
import com.pharmacore.safety.domain.SafetyRule;
import com.pharmacore.safety.domain.SafetyRules;
import com.pharmacore.safety.domain.SubstanceNames;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counter vigilance: evaluates a basket against the referential, the customer's file and their
 * recent dispensings. Used live by the terminal ({@code POST /api/v1/safety/check}) and again when
 * a sale is recorded, where a contraindication without the pharmacist's override is an anomaly.
 */
@Service
public class SafetyCheckService {

    public record CheckResult(List<SafetyAlert> alerts, boolean profileKnown, int blocking) {
    }

    private final ProductRepository products;
    private final SafetyRuleStore rules;
    private final HealthProfileService profiles;
    private final TenantRepository tenants;
    private final TenantCalendar calendar;
    private final AlertService alerts;
    private final AuditTrail audit;
    private final JdbcClient jdbc;

    public SafetyCheckService(ProductRepository products, SafetyRuleStore rules, HealthProfileService profiles,
                              TenantRepository tenants, TenantCalendar calendar, AlertService alerts, AuditTrail audit,
                              JdbcClient jdbc) {
        this.products = products;
        this.rules = rules;
        this.profiles = profiles;
        this.tenants = tenants;
        this.calendar = calendar;
        this.alerts = alerts;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CheckResult check(UUID customerId, Collection<UUID> productIds) {
        List<SafetyAlert> found = evaluate(customerId, productIds);
        PatientProfile profile = profiles.profileOf(customerId);
        return new CheckResult(found, customerId != null && !profile.isEmpty(),
                (int) found.stream().filter(SafetyAlert::blocking).count());
    }

    /** Every alert of the basket of a sale about to be recorded (same transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SafetyAlert> evaluate(SaleDraft draft) {
        return evaluate(draft.customerId(), draft.lines().stream().map(SaleDraft.Line::productId).toList());
    }

    private List<SafetyAlert> evaluate(UUID customerId, Collection<UUID> productIds) {
        Set<UUID> ids = new LinkedHashSet<>(productIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> order = List.copyOf(ids);
        List<DispensedItem> items = products.findByIdIn(ids).stream()
                .sorted(java.util.Comparator.comparingInt(product -> order.indexOf(product.id())))
                .map(product -> {
                    var data = product.data();
                    return new DispensedItem(product.id(), product.designation(), data.dci(), data.atcCode());
                })
                .toList();
        PatientProfile profile = profiles.profileOf(customerId);
        LocalDate today = calendar.today();
        List<RecentDispensing> recent = customerId == null ? List.of() : recentDispensings(customerId, today);
        Set<String> tokens = new LinkedHashSet<>();
        for (DispensedItem item : items) {
            item.components().forEach(component -> tokens.addAll(SubstanceNames.prefixes(component)));
        }
        for (RecentDispensing dispensing : recent) {
            dispensing.components().forEach(component -> tokens.addAll(SubstanceNames.prefixes(component)));
        }
        List<SafetyRule> applicable = tokens.isEmpty() ? List.of() : rules.matching(tokens);
        return SafetyRules.evaluate(items, applicable, profile, recent, today);
    }

    /**
     * Keeps the pharmacist's decisions on the alerts of the recorded sale, and tells the owner
     * (staff name, products and reason: never the customer).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOverrides(SaleEntity sale, SaleDraft draft, List<SafetyAlert> found, UUID decidedBy, Instant now) {
        if (draft.safetyOverrides().isEmpty()) {
            return;
        }
        Map<String, SafetyAlert> byKey = found.stream()
                .collect(Collectors.toMap(SafetyAlert::key, alert -> alert, (a, b) -> a));
        UUID tenant = TenantContext.requireTenant().value();
        for (SaleDraft.SafetyOverride override : draft.safetyOverrides()) {
            SafetyAlert alert = byKey.get(override.alertKey());
            if (alert == null) {
                continue;
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO dispensing_safety_override (id, tenant_id, sale_id, customer_id, alert_key, kind, severity,
                                                                    title, detail, reason, decided_by, decided_at)
                            VALUES (:id, :tenant, :sale, :customer, :key, :kind, :severity, :title, :detail, :reason, :by, :at)""")
                    .param("id", id)
                    .param("tenant", tenant)
                    .param("sale", sale.id())
                    .param("customer", draft.customerId())
                    .param("key", alert.key())
                    .param("kind", alert.kind().name())
                    .param("severity", alert.severity().name())
                    .param("title", alert.title())
                    .param("detail", alert.detail())
                    .param("reason", override.reason())
                    .param("by", decidedBy)
                    .param("at", Timestamp.from(now))
                    .update();
            audit.record("SAFETY_OVERRIDE", "SALE", sale.id(), Map.of(
                    "alert", alert.title(), "severity", alert.severity().name(), "reason", override.reason()));
            if (alert.blocking()) {
                alerts.raise(new ControlAlertEntity.Draft(ControlAlertEntity.Kind.SAFETY_OVERRIDE,
                        ControlAlertEntity.Severity.WARNING, "Contre-indication passée outre",
                        truncate(alert.title() + " — motif : " + override.reason(), 500),
                        "SALE", sale.id(), decidedBy, null, now));
            }
        }
    }

    private List<RecentDispensing> recentDispensings(UUID customerId, LocalDate today) {
        UUID tenant = TenantContext.requireTenant().value();
        ZoneId zone = tenants.findById(tenant).map(t -> ZoneId.of(t.timeZone())).orElse(ZoneId.of("UTC"));
        Instant since = today.minusDays(SafetyRules.RECENT_DAYS).atStartOfDay(zone).toInstant();
        return jdbc.sql("""
                        SELECT l.product_id, p.designation, p.dci, p.atc_code,
                               (s.occurred_at AT TIME ZONE :zone)::date AS dispensed_on
                        FROM sale s
                        JOIN sale_line l ON l.sale_id = s.id
                        JOIN product p ON p.id = l.product_id
                        WHERE s.tenant_id = :tenant AND s.customer_id = :customer AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :since
                        ORDER BY s.occurred_at DESC
                        LIMIT 200""")
                .param("tenant", tenant)
                .param("customer", customerId)
                .param("since", Timestamp.from(since))
                .param("zone", zone.getId())
                .query((rs, rowNum) -> new RecentDispensing(rs.getObject("product_id", UUID.class),
                        rs.getString("designation"), rs.getString("dci"), rs.getString("atc_code"),
                        rs.getDate("dispensed_on").toLocalDate()))
                .list();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
