package com.pharmacore.subscription.application;

import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.subscription.application.SubscriptionViews.LicenseStatus;
import com.pharmacore.subscription.application.SubscriptionViews.LicenseView;
import com.pharmacore.subscription.application.SubscriptionViews.PharmacySubscription;
import com.pharmacore.subscription.application.SubscriptionViews.PlanView;
import com.pharmacore.subscription.application.SubscriptionViews.StateView;
import com.pharmacore.subscription.application.SubscriptionViews.VendorContact;
import com.pharmacore.subscription.domain.LicenseClaims;
import com.pharmacore.subscription.domain.LicenseState;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * The pharmacy's own view of its subscription. On a hub everything comes from the signed
 * license and the replicated payment history, so the page works offline.
 */
@Service
public class PharmacySubscriptionService {

    private final LicenseStateService states;
    private final HubLicenseService hub;
    private final LicenseRefresher refresher;
    private final NodeProperties node;
    private final SubscriptionProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public PharmacySubscriptionService(LicenseStateService states, HubLicenseService hub,
                                       LicenseRefresher refresher, NodeProperties node,
                                       SubscriptionProperties properties, JdbcClient jdbc,
                                       PlatformTransactionManager transactionManager, Clock clock) {
        this.states = states;
        this.hub = hub;
        this.refresher = refresher;
        this.node = node;
        this.properties = properties;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    public LicenseStatus status() {
        TenantId tenant = TenantContext.requireTenant();
        LicenseState state = states.current(tenant);
        Instant now = clock.instant();
        LicenseClaims claims = state.claims();
        LicenseView license = node.isHub()
                ? hub.held().map(h -> new LicenseView(h.claims().serial(), h.claims().issuedAt(),
                        h.claims().validUntil(), h.verifiedAt())).orElse(new LicenseView(null, null, null, null))
                : readOnly.execute(status -> storedLicense(tenant, now));
        return new LicenseStatus(StateView.of(state), claims == null ? null : claims.plan(),
                claims == null ? null : claims.paidUntil(), claims == null ? null : claims.graceUntil(), license,
                node.mode().name(), now);
    }

    public PharmacySubscription mine() {
        TenantId tenant = TenantContext.requireTenant();
        LicenseStatus status = status();
        return readOnly.execute(tx -> {
            PlanView plan = status.plan() == null ? null : jdbc.sql("""
                            SELECT code, name, monthly_price_xof, max_terminals, description, active
                            FROM subscription_plan WHERE code = :code""")
                    .param("code", status.plan())
                    .query((rs, rowNum) -> new PlanView(rs.getString("code"), rs.getString("name"),
                            rs.getLong("monthly_price_xof"), rs.getInt("max_terminals"), rs.getString("description"),
                            rs.getBoolean("active")))
                    .optional()
                    .orElse(null);
            return new PharmacySubscription(status, plan, PaymentQueries.payments(jdbc, tenant.value()),
                    new VendorContact(properties.vendorName(), properties.vendorEmail(), properties.vendorPhone()));
        });
    }

    /** Explicit pull of the license (hub) or of the subscription state (cloud). */
    public LicenseStatus refresh() {
        TenantId tenant = TenantContext.requireTenant();
        if (node.isHub()) {
            try {
                refresher.refresh();
            } catch (RestClientException e) {
                throw new BusinessRuleException("cloud-unreachable",
                        "Cloud injoignable : la licence sera renouvelée à la prochaine connexion à Internet");
            }
        } else {
            states.evict(tenant.value());
        }
        return status();
    }

    private LicenseView storedLicense(TenantId tenant, Instant now) {
        return jdbc.sql("SELECT serial, issued_at, valid_until FROM tenant_license WHERE tenant_id = :tenant")
                .param("tenant", tenant.value())
                .query((rs, rowNum) -> new LicenseView(rs.getLong("serial"), instant(rs.getTimestamp("issued_at")),
                        instant(rs.getTimestamp("valid_until")), now))
                .optional()
                .orElse(new LicenseView(null, null, null, now));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
