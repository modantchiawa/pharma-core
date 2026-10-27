package com.pharmacore.demo;

import com.pharmacore.subscription.application.PlatformSubscriptionService;
import com.pharmacore.subscription.application.PlatformSubscriptionService.PaymentCommand;
import com.pharmacore.subscription.domain.SubscriptionPaymentMethod;
import com.pharmacore.tenant.application.TenantProvisioningService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscriptions of the demo pharmacies, run as the platform: the Sahel pays its Pro plan well
 * ahead (three payments on file, the last one recorded through the console's service);
 * Lacouroussou is on the Essentiel plan and its paid period ends in five days, so the
 * renewal warning shows from the first login.
 */
final class DemoSubscriptions {

    private static final String VENDOR = "Démonstration";

    private DemoSubscriptions() {
    }

    static void seed(TenantProvisioningService provisioning, PlatformSubscriptionService subscriptions,
                     JdbcClient jdbc, TransactionTemplate tx, Clock clock, UUID sahel, UUID lacouroussou) {
        Instant now = clock.instant();
        ZonedDateTime start = now.atZone(DemoDataSeeder.ZONE).minusMonths(8).withHour(10).withMinute(0)
                .withSecond(0).withNano(0);
        ZonedDateTime renewed = start.plusMonths(3);
        ZonedDateTime current = renewed.plusMonths(6);
        payment(jdbc, tx, sahel, "PRO", 135_000, SubscriptionPaymentMethod.MOBILE_MONEY, "Airtel Money · 58213497",
                3, start, start.toLocalDate().minusDays(2), "Mise en service");
        payment(jdbc, tx, sahel, "PRO", 270_000, SubscriptionPaymentMethod.VIREMENT, "Virement BOA 2026-0412",
                6, renewed, renewed.toLocalDate().minusDays(3), null);
        provisioning.renewSubscription(sahel, "PRO", current.toInstant());
        subscriptions.recordPayment(sahel, new PaymentCommand(270_000, SubscriptionPaymentMethod.MOBILE_MONEY,
                "Orange Money · 77310245", 6, LocalDate.now(clock).minusDays(12), "Renouvellement semestriel"));

        ZonedDateTime end = now.plus(Duration.ofDays(5)).atZone(DemoDataSeeder.ZONE).withHour(23).withMinute(59)
                .withSecond(0).withNano(0);
        ZonedDateTime quarter = end.minusMonths(3);
        ZonedDateTime first = quarter.minusMonths(1);
        subscriptions.changePlan(lacouroussou, "ESSENTIEL");
        payment(jdbc, tx, lacouroussou, "ESSENTIEL", 25_000, SubscriptionPaymentMethod.ESPECES, "Reçu n° 0187",
                1, first, first.toLocalDate(), "Mois d'essai payé au comptoir de l'éditeur");
        payment(jdbc, tx, lacouroussou, "ESSENTIEL", 75_000, SubscriptionPaymentMethod.MOBILE_MONEY,
                "Moov Money · 30921184", 3, quarter, quarter.toLocalDate().minusDays(1), null);
        provisioning.renewSubscription(lacouroussou, "ESSENTIEL", end.toInstant());
    }

    /** Payment received before the demo starts: written with its own period, as recorded at the time. */
    private static void payment(JdbcClient jdbc, TransactionTemplate tx, UUID tenant, String plan, long amountXof,
                                SubscriptionPaymentMethod method, String reference, int months, ZonedDateTime from,
                                LocalDate paidAt, String note) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO subscription_payment (id, tenant_id, plan_code, amount_xof, method, reference, months,
                                                          period_from, period_to, paid_at, recorded_by, recorded_at, note)
                        VALUES (:id, :tenant, :plan, :amount, :method, :reference, :months, :from, :to, :paidAt,
                                :by, :recordedAt, :note)""")
                .param("id", UUID.randomUUID())
                .param("tenant", tenant)
                .param("plan", plan)
                .param("amount", amountXof)
                .param("method", method.name())
                .param("reference", reference)
                .param("months", months)
                .param("from", Timestamp.from(from.toInstant()))
                .param("to", Timestamp.from(from.plusMonths(months).toInstant()))
                .param("paidAt", Date.valueOf(paidAt))
                .param("by", VENDOR)
                .param("recordedAt", Timestamp.from(paidAt.atTime(11, 0).atZone(DemoDataSeeder.ZONE).toInstant()))
                .param("note", note)
                .update());
    }
}
