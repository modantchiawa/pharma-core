package com.pharmacore.reporting.application;

import com.pharmacore.control.application.AlertService;
import com.pharmacore.control.persistence.ControlAlertEntity;
import com.pharmacore.followup.application.FollowUpSettingsService;
import com.pharmacore.followup.application.SmsGatewayPort;
import com.pharmacore.followup.domain.PhoneNumbers;
import com.pharmacore.printing.domain.ReceiptLayout;
import com.pharmacore.reporting.domain.DailyDigest;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.application.BrandingService;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner's evening report: the day's figures as a short text, raised as an alert (bell,
 * webhook) at the hour the pharmacy chose and sent by SMS to the owner's phone when a gateway
 * is configured; readable any time from the dashboard, and shareable on WhatsApp in one tap.
 */
@Service
public class DailyDigestService {

    private static final Map<String, String> METHOD_LABELS = Map.of(
            "CASH", "Espèces", "CARD", "Carte", "CHECK", "Chèque", "MOBILE_MONEY", "Mobile money",
            "THIRD_PARTY_PAYER", "Tiers payant", "CREDIT", "Crédit", "VOUCHER", "Avoir");

    public record DigestView(LocalDate date, String summary, String message, String whatsappLink,
                             boolean alreadyPublished) {
    }

    private final DashboardService dashboard;
    private final FollowUpSettingsService settings;
    private final BrandingService branding;
    private final AlertService alerts;
    private final SmsGatewayPort sms;
    private final TenantRepository tenants;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DailyDigestService(DashboardService dashboard, FollowUpSettingsService settings, BrandingService branding,
                              AlertService alerts, SmsGatewayPort sms, TenantRepository tenants, AuditTrail audit, JdbcClient jdbc, PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.dashboard = dashboard;
        this.settings = settings;
        this.branding = branding;
        this.alerts = alerts;
        this.sms = sms;
        this.tenants = tenants;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DigestView view(LocalDate date) {
        DailyDigest digest = build(date);
        String currency = branding.current().currency();
        String message = digest.message(cents -> ReceiptLayout.money(cents, currency));
        String phone = settings.effective().values().digestPhone();
        if (phone == null) {
            phone = branding.current().branding().phone();
        }
        String link = PhoneNumbers.international(phone, branding.current().branding().countryCode())
                .map(digits -> PhoneNumbers.whatsappLink(digits, message)).orElse(null);
        return new DigestView(date, digest.summary(cents -> ReceiptLayout.money(cents, currency)), message, link,
                publishedOn(date));
    }

    @Transactional(readOnly = true)
    public DailyDigest build(LocalDate date) {
        DashboardService.Dashboard d = dashboard.build(date, date);
        Map<String, Long> byMethod = new LinkedHashMap<>();
        for (DashboardService.PaymentShare share : d.payments()) {
            byMethod.merge(METHOD_LABELS.getOrDefault(share.method(), share.method()), share.amountCents(), Long::sum);
        }
        List<DailyDigest.Payment> payments = new ArrayList<>();
        byMethod.forEach((label, amount) -> payments.add(new DailyDigest.Payment(label, amount)));
        long pendingMessages = jdbc.sql("""
                        SELECT count(*) FROM customer_message WHERE tenant_id = :tenant AND status = 'PENDING'""")
                .param("tenant", TenantContext.requireTenant().value())
                .query(Long.class).single();
        return new DailyDigest(date, branding.current().branding().displayName(), d.sales().count(),
                d.sales().netCents(), d.sales().previousNetCents(), d.sales().marginCents(),
                d.sales().averageBasketCents(), d.sales().cancelledCount(), payments, d.cash().differenceCents(),
                d.cash().expensesCents(), d.cash().sessionsClosed(), d.stock().belowThreshold(),
                d.stock().shortfallProducts(), d.stock().expiringLots(), d.receivables().customerCreditCents(),
                d.receivables().debtors(), pendingMessages);
    }

    /**
     * Raises the alert (and queues it for the webhook) in one transaction, then sends the SMS
     * outside it. Idempotent for a day: the alert is raised once.
     *
     * @return the alert's identifier, empty when the day was already published
     */
    public Optional<UUID> publish(LocalDate date) {
        Optional<UUID> raised = tx.execute(status -> {
            if (publishedOn(date)) {
                return Optional.<UUID>empty();
            }
            String currency = branding.current().currency();
            DailyDigest digest = build(date);
            Instant now = clock.instant();
            var view = alerts.raise(new ControlAlertEntity.Draft(ControlAlertEntity.Kind.DAILY_DIGEST,
                    ControlAlertEntity.Severity.INFO, "Rapport du " + date,
                    digest.summary(cents -> ReceiptLayout.money(cents, currency)), "DAILY_DIGEST", null, null,
                    digest.netCents(), now));
            audit.record("DAILY_DIGEST_PUBLISHED", "DAILY_DIGEST", date.toString(), Map.of(
                    "netCents", Long.toString(digest.netCents()), "sales", Long.toString(digest.sales())));
            return Optional.of(view.id());
        });
        if (raised == null || raised.isEmpty()) {
            return Optional.empty();
        }
        sendSms(date);
        return raised;
    }

    private void sendSms(LocalDate date) {
        var effective = tx.execute(status -> settings.effective());
        if (effective == null || !effective.values().smsConfigured() || effective.values().digestPhone() == null) {
            return;
        }
        String country = tx.execute(status -> branding.current().branding().countryCode());
        Optional<String> digits = PhoneNumbers.international(effective.values().digestPhone(), country);
        if (digits.isEmpty()) {
            return;
        }
        String summary = tx.execute(status -> {
            String currency = branding.current().currency();
            return build(date).summary(cents -> ReceiptLayout.money(cents, currency));
        });
        Optional<String> error = sms.send(new SmsGatewayPort.Gateway(effective.values().smsGatewayUrl(),
                effective.smsGatewayToken(), effective.values().smsSender()), digits.get(), summary);
        tx.executeWithoutResult(status -> audit.record("DAILY_DIGEST_SMS", "DAILY_DIGEST", date.toString(), Map.of(
                "status", error.isEmpty() ? "SENT" : "FAILED", "error", error.orElse(""))));
    }

    /** Whether the pharmacy's clock has reached the chosen hour today and the digest is not out yet. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> dueNow() {
        var values = settings.effective().values();
        if (!values.digestEnabled()) {
            return Optional.empty();
        }
        ZoneId zone = tenants.findById(TenantContext.requireTenant().value())
                .map(t -> ZoneId.of(t.timeZone())).orElse(ZoneId.of("UTC"));
        ZonedDateTime now = clock.instant().atZone(zone);
        if (now.getHour() < values.digestHour()) {
            return Optional.empty();
        }
        LocalDate today = now.toLocalDate();
        return publishedOn(today) ? Optional.empty() : Optional.of(today);
    }

    private boolean publishedOn(LocalDate date) {
        return jdbc.sql("""
                        SELECT count(*) FROM control_alert
                        WHERE tenant_id = :tenant AND kind = 'DAILY_DIGEST' AND title = :title""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("title", "Rapport du " + date)
                .query(Long.class).single() > 0;
    }
}
