package com.pharmacore.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.subscription.domain.LicenseState.Reason;
import com.pharmacore.subscription.domain.LicenseState.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LicenseEvaluatorTest {

    private static final LicensePolicy POLICY = LicensePolicy.DEFAULT;
    private static final Instant ISSUED = Instant.parse("2026-10-01T08:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();

    /** A hub license issued at {@link #ISSUED}, paid until {@code paidUntil} (3 days of grace). */
    private static LicenseClaims hub(Instant paidUntil) {
        return new LicenseClaims(TENANT, "sahel", "PRO", 5, LicenseClaims.Status.ACTIVE, paidUntil,
                paidUntil == null ? null : paidUntil.plus(POLICY.grace()), null, ISSUED,
                ISSUED.plus(POLICY.offlineWindow()), 4);
    }

    /** The cloud's live view: no offline window. */
    private static LicenseClaims cloud(Instant paidUntil) {
        return new LicenseClaims(TENANT, "sahel", "PRO", 5, LicenseClaims.Status.ACTIVE, paidUntil,
                paidUntil == null ? null : paidUntil.plus(POLICY.grace()), null, ISSUED, null, 0);
    }

    private static LicenseState evaluate(LicenseClaims claims, Instant now) {
        return LicenseEvaluator.evaluate(claims, now, null, null, POLICY);
    }

    @Test
    void paidPeriodWellAheadIsActive() {
        var state = evaluate(cloud(ISSUED.plus(Duration.ofDays(60))), ISSUED.plus(Duration.ofDays(1)));

        assertThat(state.status()).isEqualTo(Status.ACTIVE);
        assertThat(state.reason()).isNull();
        assertThat(state.blocksAt()).isEqualTo(ISSUED.plus(Duration.ofDays(63)));
        assertThat(state.blocked()).isFalse();
    }

    @Test
    void subscriptionWithoutEndDateNeverExpiresOnTheCloud() {
        var state = evaluate(cloud(null), ISSUED.plus(Duration.ofDays(4_000)));

        assertThat(state.status()).isEqualTo(Status.ACTIVE);
        assertThat(state.blocksAt()).isNull();
    }

    @Test
    void warningStartsSevenDaysBeforeThePaidPeriodEnds() {
        Instant paidUntil = ISSUED.plus(Duration.ofDays(30));

        assertThat(evaluate(cloud(paidUntil), paidUntil.minus(Duration.ofDays(7)).minusSeconds(1)).status())
                .isEqualTo(Status.ACTIVE);
        var warning = evaluate(cloud(paidUntil), paidUntil.minus(Duration.ofDays(7)));
        assertThat(warning.status()).isEqualTo(Status.WARNING);
        assertThat(warning.reason()).isEqualTo(Reason.PAYMENT_DUE);
        assertThat(warning.daysLeft()).isEqualTo(7);
        assertThat(warning.deadline()).isEqualTo(paidUntil);
        // 2 days and 1 hour left reads « 3 jours »
        assertThat(evaluate(cloud(paidUntil), paidUntil.minus(Duration.ofHours(49))).daysLeft()).isEqualTo(3);
    }

    @Test
    void threeDaysOfGraceAfterThePaidPeriodThenBlocked() {
        Instant paidUntil = ISSUED.plus(Duration.ofDays(30));

        var grace = evaluate(cloud(paidUntil), paidUntil.plus(Duration.ofHours(1)));
        assertThat(grace.status()).isEqualTo(Status.GRACE);
        assertThat(grace.reason()).isEqualTo(Reason.EXPIRED);
        assertThat(grace.deadline()).isEqualTo(paidUntil.plus(Duration.ofDays(3)));
        assertThat(grace.daysLeft()).isEqualTo(3);
        assertThat(grace.blocked()).isFalse();

        var blocked = evaluate(cloud(paidUntil), paidUntil.plus(Duration.ofDays(3)));
        assertThat(blocked.status()).isEqualTo(Status.BLOCKED);
        assertThat(blocked.reason()).isEqualTo(Reason.EXPIRED);
        assertThat(blocked.blockedSince()).isEqualTo(paidUntil.plus(Duration.ofDays(3)));
        assertThat(blocked.blockedMessage()).startsWith("Abonnement expiré");
    }

    @Test
    void suspensionBlocksAtOnceAndIsDated() {
        Instant suspendedAt = ISSUED.minus(Duration.ofHours(2));
        var claims = new LicenseClaims(TENANT, "sahel", "PRO", 5, LicenseClaims.Status.SUSPENDED,
                ISSUED.plus(Duration.ofDays(200)), ISSUED.plus(Duration.ofDays(203)), suspendedAt, ISSUED,
                ISSUED.plus(POLICY.offlineWindow()), 9);

        var state = evaluate(claims, ISSUED.plusSeconds(60));

        assertThat(state.status()).isEqualTo(Status.BLOCKED);
        assertThat(state.reason()).isEqualTo(Reason.SUSPENDED);
        assertThat(state.blockedSince()).isEqualTo(suspendedAt);
        assertThat(state.admitsSaleAt(suspendedAt.minusSeconds(1))).isTrue();
        assertThat(state.admitsSaleAt(suspendedAt.plusSeconds(1))).isFalse();
        assertThat(state.blockedMessage()).contains("suspendu").contains("ordonnancier");
    }

    @Test
    void hubBlocksItselfFifteenDaysAfterItsLastLicenseWithWarningsFromDayMinusFive() {
        var claims = hub(ISSUED.plus(Duration.ofDays(365)));
        Instant limit = ISSUED.plus(Duration.ofDays(15));

        assertThat(evaluate(claims, limit.minus(Duration.ofDays(5)).minusSeconds(1)).status()).isEqualTo(Status.ACTIVE);
        var warning = evaluate(claims, limit.minus(Duration.ofDays(5)));
        assertThat(warning.status()).isEqualTo(Status.WARNING);
        assertThat(warning.reason()).isEqualTo(Reason.OFFLINE_LIMIT);
        assertThat(warning.daysLeft()).isEqualTo(5);
        assertThat(warning.blocksAt()).isEqualTo(limit);

        var blocked = evaluate(claims, limit);
        assertThat(blocked.status()).isEqualTo(Status.BLOCKED);
        assertThat(blocked.reason()).isEqualTo(Reason.OFFLINE_TOO_LONG);
        assertThat(blocked.blockedSince()).isEqualTo(limit);
    }

    @Test
    void nearestDeadlineWinsWhenPaymentAndOfflineWarningsOverlap() {
        Instant paidUntil = ISSUED.plus(Duration.ofDays(16));
        var state = evaluate(hub(paidUntil), ISSUED.plus(Duration.ofDays(12)));

        assertThat(state.reason()).isEqualTo(Reason.OFFLINE_LIMIT);
        assertThat(state.deadline()).isEqualTo(ISSUED.plus(Duration.ofDays(15)));

        var paymentFirst = evaluate(hub(ISSUED.plus(Duration.ofDays(13))), ISSUED.plus(Duration.ofDays(12)));
        assertThat(paymentFirst.reason()).isEqualTo(Reason.PAYMENT_DUE);
    }

    @Test
    void clockSetBackBeyondTheToleranceIsTampering() {
        var claims = hub(ISSUED.plus(Duration.ofDays(365)));
        Instant watermark = ISSUED.plus(Duration.ofDays(20));

        var tampered = LicenseEvaluator.evaluate(claims, ISSUED.plus(Duration.ofDays(3)), watermark, null, POLICY);
        assertThat(tampered.status()).isEqualTo(Status.BLOCKED);
        assertThat(tampered.reason()).isEqualTo(Reason.CLOCK_TAMPERED);
        assertThat(tampered.blockedSince()).isNull();
        assertThat(tampered.admitsSaleAt(ISSUED)).isFalse();
        assertThat(tampered.blockedMessage()).startsWith("Horloge du poste incohérente");

        // a few minutes back (time synchronization) is tolerated
        var adjusted = LicenseEvaluator.evaluate(claims, ISSUED.plus(Duration.ofDays(2)),
                ISSUED.plus(Duration.ofDays(2)).plus(Duration.ofMinutes(9)), null, POLICY);
        assertThat(adjusted.status()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void noLicenseIsBlockedUnlessAnUpgradedHubIsStillWithinItsWindow() {
        var none = LicenseEvaluator.evaluate(null, ISSUED, null, null, POLICY);
        assertThat(none.status()).isEqualTo(Status.BLOCKED);
        assertThat(none.reason()).isEqualTo(Reason.NO_LICENSE);

        var upgraded = LicenseEvaluator.evaluate(null, ISSUED.plus(Duration.ofDays(11)), null, ISSUED, POLICY);
        assertThat(upgraded.status()).isEqualTo(Status.WARNING);
        assertThat(upgraded.reason()).isEqualTo(Reason.NO_LICENSE);
        assertThat(upgraded.daysLeft()).isEqualTo(4);

        var late = LicenseEvaluator.evaluate(null, ISSUED.plus(Duration.ofDays(15)), null, ISSUED, POLICY);
        assertThat(late.status()).isEqualTo(Status.BLOCKED);
        assertThat(late.reason()).isEqualTo(Reason.NO_LICENSE);
    }
}
