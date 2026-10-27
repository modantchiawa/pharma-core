package com.pharmacore.control.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pharmacore.control.domain.FourEyesPolicy.Action;
import com.pharmacore.control.domain.FourEyesPolicy.Requirement;
import com.pharmacore.control.domain.FourEyesPolicy.Thresholds;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlRulesTest {

    private static final Thresholds THRESHOLDS = new Thresholds(10_000, 5_000, 5_000);

    @Test
    void fourEyesAboveTheThresholdOfTheActionAndAlwaysForNarcotics() {
        assertThat(FourEyesPolicy.requirement(Action.STOCK_WRITE_OFF, 10_000, false, THRESHOLDS)).isEmpty();
        assertThat(FourEyesPolicy.requirement(Action.STOCK_WRITE_OFF, 10_001, false, THRESHOLDS))
                .containsInstanceOf(Requirement.AboveThreshold.class);
        // a loss and a surplus weigh the same
        assertThat(FourEyesPolicy.requirement(Action.STOCK_ADJUSTMENT, -12_000, false, THRESHOLDS)).isPresent();
        assertThat(FourEyesPolicy.requirement(Action.SALE_RETURN, 5_001, false, THRESHOLDS)).isPresent();
        assertThat(FourEyesPolicy.requirement(Action.SALE_CANCELLATION, 4_999, false, THRESHOLDS)).isEmpty();
        assertThat(FourEyesPolicy.requirement(Action.SALE_CANCELLATION, 1, true, THRESHOLDS))
                .containsInstanceOf(Requirement.Narcotic.class);
        assertThat(FourEyesPolicy.requirement(Action.STOCK_COUNT, 0, true, THRESHOLDS)).isPresent();
    }

    @Test
    void aZeroThresholdSwitchesTheRuleOffExceptForNarcotics() {
        var off = new Thresholds(0, 0, 0);
        assertThat(FourEyesPolicy.requirement(Action.STOCK_WRITE_OFF, 1_000_000, false, off)).isEmpty();
        assertThat(FourEyesPolicy.requirement(Action.STOCK_WRITE_OFF, 1, true, off)).isPresent();
    }

    @Test
    void activityHoursPerWeekday() {
        var hours = ActivityHours.of(List.of(
                new ActivityHours.Day(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(20, 0)),
                new ActivityHours.Day(DayOfWeek.SUNDAY, LocalTime.of(9, 0), LocalTime.of(13, 0))));
        LocalDateTime monday = LocalDateTime.of(2026, 10, 26, 8, 0);
        assertThat(hours.isOpen(monday)).isTrue();
        assertThat(hours.isOpen(monday.minusMinutes(1))).isFalse();
        assertThat(hours.isOpen(monday.withHour(20))).isFalse();
        assertThat(hours.isOpen(monday.plusDays(1).withHour(10))).isFalse();
        assertThat(hours.isOpen(LocalDateTime.of(2026, 11, 1, 12, 59))).isTrue();
        assertThat(ActivityHours.defaults().isOpen(LocalDateTime.of(2026, 11, 1, 21, 59))).isTrue();
        assertThat(ActivityHours.defaults().isOpen(LocalDateTime.of(2026, 11, 1, 23, 40))).isFalse();
    }

    @Test
    void activityHoursRefuseInvertedOrDuplicatedDays() {
        assertThatThrownBy(() -> ActivityHours.of(List.of(
                new ActivityHours.Day(DayOfWeek.MONDAY, LocalTime.of(20, 0), LocalTime.of(8, 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActivityHours.of(List.of(
                new ActivityHours.Day(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new ActivityHours.Day(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(18, 0)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webhookSignatureIsAnHmacOfTimestampAndBody() {
        String body = "{\"event\":\"control.alert\"}";
        String signature = WebhookSignature.sign("shared-secret-of-the-owner", 1_793_000_000L, body);
        assertThat(signature).startsWith("sha256=").hasSize(7 + 64);
        assertThat(WebhookSignature.verify("shared-secret-of-the-owner", 1_793_000_000L, body, signature)).isTrue();
        assertThat(WebhookSignature.verify("another-secret", 1_793_000_000L, body, signature)).isFalse();
        assertThat(WebhookSignature.verify("shared-secret-of-the-owner", 1_793_000_001L, body, signature)).isFalse();
        assertThat(WebhookSignature.verify("shared-secret-of-the-owner", 1_793_000_000L, body + " ", signature))
                .isFalse();
    }

    @Test
    void deliveryBackoffDoublesUpToTwoHoursThenGivesUp() {
        assertThat(DeliveryBackoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(DeliveryBackoff.delayAfter(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(DeliveryBackoff.delayAfter(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(DeliveryBackoff.delayAfter(12)).isEqualTo(Duration.ofHours(2));
        assertThat(DeliveryBackoff.delayAfter(39)).isEqualTo(Duration.ofHours(2));
        assertThat(DeliveryBackoff.exhausted(39)).isFalse();
        assertThat(DeliveryBackoff.exhausted(40)).isTrue();
    }

    @Test
    void amountsAreWrittenForASms() {
        assertThat(Amounts.format(1_234_567, "XOF")).isEqualTo("1 234 567 F CFA");
        assertThat(Amounts.format(-3_000, "XOF")).isEqualTo("-3 000 F CFA");
        assertThat(Amounts.format(12_345, "EUR")).isEqualTo("123,45 EUR");
        assertThat(Amounts.format(5, "EUR")).isEqualTo("0,05 EUR");
    }
}
