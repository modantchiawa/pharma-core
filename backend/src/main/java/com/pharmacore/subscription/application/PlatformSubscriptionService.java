package com.pharmacore.subscription.application;

import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.subscription.application.SubscriptionViews.HubNodeView;
import com.pharmacore.subscription.application.SubscriptionViews.LicenseView;
import com.pharmacore.subscription.application.SubscriptionViews.PlanView;
import com.pharmacore.subscription.application.SubscriptionViews.StateView;
import com.pharmacore.subscription.application.SubscriptionViews.TenantSubscription;
import com.pharmacore.subscription.application.SubscriptionViews.TenantSubscriptionDetail;
import com.pharmacore.subscription.domain.LicenseClaims;
import com.pharmacore.subscription.domain.LicenseEvaluator;
import com.pharmacore.subscription.domain.SubscriptionPaymentMethod;
import com.pharmacore.subscription.domain.SubscriptionPeriod;
import com.pharmacore.tenant.application.TenantProvisioningService;
import com.pharmacore.tenant.application.TenantSubscriptionChanged;
import com.pharmacore.tenant.persistence.TenantRepository;
import com.pharmacore.tenant.persistence.TenantStatus;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vendor console: subscriptions of every pharmacy, payments received outside the application
 * (each one extends the paid period), plan changes, suspension and reactivation. Every change
 * re-issues the pharmacy's license.
 */
@Service
public class PlatformSubscriptionService {

    public record PaymentCommand(long amountXof, SubscriptionPaymentMethod method, String reference, int months,
                                 LocalDate paidAt, String note) {
    }

    private static final String SUBSCRIPTIONS = """
            SELECT t.id, t.slug, t.legal_name, t.status, t.topology, t.subscription_plan, t.subscription_expires_at,
                   t.suspended_at, t.suspension_reason,
                   p.name AS plan_name, coalesce(p.monthly_price_xof, 0) AS monthly_price_xof,
                   coalesce(p.max_terminals, 1) AS max_terminals,
                   lp.paid_at AS last_paid_at, lp.amount_xof AS last_amount_xof,
                   l.serial, l.issued_at, l.valid_until,
                   h.last_seen_at AS hub_seen_at
            FROM tenant t
            LEFT JOIN subscription_plan p ON p.code = t.subscription_plan
            LEFT JOIN tenant_license l ON l.tenant_id = t.id
            LEFT JOIN LATERAL (
                SELECT paid_at, amount_xof FROM subscription_payment sp
                WHERE sp.tenant_id = t.id ORDER BY paid_at DESC, recorded_at DESC LIMIT 1
            ) lp ON true
            LEFT JOIN LATERAL (
                SELECT max(last_seen_at) AS last_seen_at FROM hub_node hn WHERE hn.tenant_id = t.id
            ) h ON true
            """;

    private final JdbcClient jdbc;
    private final TenantRepository tenants;
    private final TenantProvisioningService provisioning;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final SubscriptionProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PlatformSubscriptionService(JdbcClient jdbc, TenantRepository tenants,
                                       TenantProvisioningService provisioning, AuditTrail audit,
                                       ApplicationEventPublisher events, SubscriptionProperties properties,
                                       PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.provisioning = provisioning;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public List<PlanView> plans() {
        requirePlatform();
        return tx.execute(status -> plansQuery());
    }

    public List<TenantSubscription> overview() {
        requirePlatform();
        Instant now = clock.instant();
        return tx.execute(status -> jdbc.sql(SUBSCRIPTIONS + " ORDER BY t.legal_name")
                .query((rs, rowNum) -> subscription(rs, now))
                .list());
    }

    public TenantSubscriptionDetail detail(UUID tenantId) {
        requirePlatform();
        Instant now = clock.instant();
        return tx.execute(status -> {
            var subscription = jdbc.sql(SUBSCRIPTIONS + " WHERE t.id = :tenant")
                    .param("tenant", tenantId)
                    .query((rs, rowNum) -> subscription(rs, now))
                    .optional()
                    .orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            return new TenantSubscriptionDetail(subscription, PaymentQueries.payments(jdbc, tenantId), hubs(tenantId));
        });
    }

    /** The paid period is extended from its end, or from today when it is already over. */
    public TenantSubscriptionDetail recordPayment(UUID tenantId, PaymentCommand command) {
        requirePlatform();
        LocalDate today = LocalDate.now(clock);
        if (command.paidAt() != null && command.paidAt().isAfter(today)) {
            throw new BusinessRuleException("payment-in-future", "La date de règlement ne peut pas être dans le futur");
        }
        if (command.months() < 1 || command.months() > SubscriptionPeriod.MAX_MONTHS) {
            throw new BusinessRuleException("invalid-months", "Durée payée invalide : de 1 à "
                    + SubscriptionPeriod.MAX_MONTHS + " mois");
        }
        tx.executeWithoutResult(status -> {
            var tenant = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            Instant now = clock.instant();
            var period = SubscriptionPeriod.extend(tenant.subscriptionExpiresAt(), now, command.months(),
                    ZoneId.of(tenant.timeZone()));
            UUID paymentId = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO subscription_payment (id, tenant_id, plan_code, amount_xof, method, reference, months,
                                                              period_from, period_to, paid_at, recorded_by, recorded_at, note)
                            VALUES (:id, :tenant, :plan, :amount, :method, :reference, :months, :from, :to, :paidAt,
                                    :recordedBy, :now, :note)""")
                    .param("id", paymentId)
                    .param("tenant", tenantId)
                    .param("plan", tenant.subscriptionPlan())
                    .param("amount", command.amountXof())
                    .param("method", command.method().name())
                    .param("reference", blankToNull(command.reference()))
                    .param("months", command.months())
                    .param("from", Timestamp.from(period.from()))
                    .param("to", Timestamp.from(period.to()))
                    .param("paidAt", Date.valueOf(command.paidAt() == null ? today : command.paidAt()))
                    .param("recordedBy", recordedBy())
                    .param("now", Timestamp.from(now))
                    .param("note", blankToNull(command.note()))
                    .update();
            tenant.renewSubscription(tenant.subscriptionPlan(), period.to(), now);
            audit.record("SUBSCRIPTION_PAYMENT_RECORDED", "TENANT", tenantId, Map.of(
                    "payment", paymentId.toString(),
                    "amountXof", Long.toString(command.amountXof()),
                    "method", command.method().name(),
                    "months", Integer.toString(command.months()),
                    "paidUntil", period.to().toString()));
        });
        events.publishEvent(new TenantSubscriptionChanged(tenantId));
        return detail(tenantId);
    }

    public TenantSubscriptionDetail changePlan(UUID tenantId, String plan) {
        requirePlatform();
        tx.executeWithoutResult(status -> {
            boolean known = jdbc.sql("SELECT active FROM subscription_plan WHERE code = :code")
                    .param("code", plan).query(Boolean.class).optional().orElse(false);
            if (!known) {
                throw new BusinessRuleException("unknown-plan", "Formule d'abonnement inconnue : " + plan);
            }
            var tenant = tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
            String previous = tenant.subscriptionPlan();
            tenant.renewSubscription(plan, tenant.subscriptionExpiresAt(), clock.instant());
            audit.record("SUBSCRIPTION_PLAN_CHANGED", "TENANT", tenantId, Map.of("from", previous, "to", plan));
        });
        events.publishEvent(new TenantSubscriptionChanged(tenantId));
        return detail(tenantId);
    }

    public TenantSubscriptionDetail suspend(UUID tenantId, String reason) {
        requirePlatform();
        provisioning.changeStatus(tenantId, TenantStatus.SUSPENDED, reason);
        return detail(tenantId);
    }

    public TenantSubscriptionDetail reactivate(UUID tenantId) {
        requirePlatform();
        provisioning.changeStatus(tenantId, TenantStatus.ACTIVE, "Réactivation par l'éditeur");
        return detail(tenantId);
    }

    private List<PlanView> plansQuery() {
        return jdbc.sql("""
                        SELECT code, name, monthly_price_xof, max_terminals, description, active
                        FROM subscription_plan ORDER BY sort_order, code""")
                .query((rs, rowNum) -> new PlanView(rs.getString("code"), rs.getString("name"),
                        rs.getLong("monthly_price_xof"), rs.getInt("max_terminals"), rs.getString("description"),
                        rs.getBoolean("active")))
                .list();
    }

    private List<HubNodeView> hubs(UUID tenantId) {
        return jdbc.sql("""
                        SELECT node_id, app_version, paired_at, last_seen_at, last_pull_at FROM hub_node
                        WHERE tenant_id = :tenant ORDER BY last_seen_at DESC NULLS LAST""")
                .param("tenant", tenantId)
                .query((rs, rowNum) -> new HubNodeView(rs.getString("node_id"), rs.getString("app_version"),
                        instant(rs.getTimestamp("paired_at")), instant(rs.getTimestamp("last_seen_at")),
                        instant(rs.getTimestamp("last_pull_at"))))
                .list();
    }

    private TenantSubscription subscription(ResultSet rs, Instant now) throws SQLException {
        UUID tenantId = rs.getObject("id", UUID.class);
        Instant paidUntil = instant(rs.getTimestamp("subscription_expires_at"));
        Instant suspendedAt = instant(rs.getTimestamp("suspended_at"));
        String status = rs.getString("status");
        var snapshot = new SubscriptionSnapshots.Snapshot(tenantId, rs.getString("slug"), status,
                rs.getString("subscription_plan"), rs.getInt("max_terminals"), paidUntil, suspendedAt);
        LicenseClaims claims = snapshot.claims(now, null, 0, properties.grace());
        var state = LicenseEvaluator.evaluate(claims, now, null, null, properties.policy());
        Long serial = rs.getObject("serial", Long.class);
        Date lastPaid = rs.getDate("last_paid_at");
        return new TenantSubscription(tenantId, rs.getString("slug"), rs.getString("legal_name"), status,
                rs.getString("topology"), rs.getString("subscription_plan"), rs.getString("plan_name"),
                rs.getLong("monthly_price_xof"), paidUntil, claims.graceUntil(), suspendedAt,
                rs.getString("suspension_reason"), lastPaid == null ? null : lastPaid.toLocalDate(),
                rs.getObject("last_amount_xof", Long.class),
                new LicenseView(serial, instant(rs.getTimestamp("issued_at")), instant(rs.getTimestamp("valid_until")),
                        null),
                instant(rs.getTimestamp("hub_seen_at")), StateView.of(state));
    }

    private static String recordedBy() {
        return switch (TenantContext.actor()) {
            case Actor.PlatformOperator operator -> operator.displayName();
            case Actor actor -> actor.auditId();
        };
    }

    private static void requirePlatform() {
        if (!(TenantContext.actor() instanceof Actor.PlatformOperator)) {
            throw new BusinessRuleException("platform-only", "Réservé à l'éditeur");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
