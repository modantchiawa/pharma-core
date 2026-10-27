package com.pharmacore.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SubscriptionRulesTest {

    private static final ZoneId NIAMEY = ZoneId.of("Africa/Niamey");
    private static final Instant NOW = Instant.parse("2026-10-27T09:00:00Z");

    @Test
    void paymentExtendsFromTheEndOfThePaidPeriodWhenItIsStillRunning() {
        Instant paidUntil = Instant.parse("2026-11-30T22:59:59Z");

        var period = SubscriptionPeriod.extend(paidUntil, NOW, 3, NIAMEY);

        assertThat(period.from()).isEqualTo(paidUntil);
        assertThat(period.to()).isEqualTo(Instant.parse("2027-02-28T22:59:59Z"));
    }

    @Test
    void latePaymentStartsTodayAndDoesNotPayForTheArrears() {
        var period = SubscriptionPeriod.extend(Instant.parse("2026-09-01T00:00:00Z"), NOW, 1, NIAMEY);

        assertThat(period.from()).isEqualTo(NOW);
        assertThat(period.to()).isEqualTo(Instant.parse("2026-11-27T09:00:00Z"));
        assertThat(SubscriptionPeriod.extend(null, NOW, 12, NIAMEY).to()).isEqualTo(Instant.parse("2027-10-27T09:00:00Z"));
    }

    @Test
    void monthsAreBounded() {
        assertThatThrownBy(() -> SubscriptionPeriod.extend(null, NOW, 0, NIAMEY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionPeriod.extend(null, NOW, 37, NIAMEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blockedPharmacyKeepsReadingAndRegularizing() {
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("GET", "/api/v1/prescriptions/register/export")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("GET", "/api/v1/accounting/income-statement")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("PUT", "/api/v1/me/password")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("PUT", "/api/v1/me/pin")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/subscription/refresh")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/hub/sync/run")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/pos/sync/sales")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/print/receipts/42")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/auth/login")).isTrue();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/sync/push")).isTrue();
    }

    @Test
    void blockedPharmacyCannotSellNorModify() {
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/sales")).isFalse();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/sales/1/cancellation")).isFalse();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/inventory/lots")).isFalse();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("PUT", "/api/v1/customers/1")).isFalse();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("DELETE", "/api/v1/guards/1")).isFalse();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("PUT", "/api/v1/print/terminals/T1")).isFalse();
        assertThat(BlockedRequestPolicy.allowedWhileBlocked("POST", "/api/v1/me/password-reset")).isFalse();
    }
}
