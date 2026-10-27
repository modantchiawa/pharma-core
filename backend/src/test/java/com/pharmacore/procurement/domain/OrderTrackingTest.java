package com.pharmacore.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.procurement.domain.OrderTracking.Scope;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OrderTrackingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test
    void overdueOnlyWhileGoodsAreAwaitedAfterTheAnnouncedDay() {
        assertThat(OrderTracking.overdue("SENT", TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(OrderTracking.overdue("PARTIALLY_RECEIVED", TODAY.minusDays(3), TODAY)).isTrue();
        // due today is not late yet
        assertThat(OrderTracking.overdue("SENT", TODAY, TODAY)).isFalse();
        assertThat(OrderTracking.overdue("SENT", null, TODAY)).isFalse();
        assertThat(OrderTracking.overdue("DRAFT", TODAY.minusDays(5), TODAY)).isFalse();
        assertThat(OrderTracking.overdue("RECEIVED", TODAY.minusDays(5), TODAY)).isFalse();
        assertThat(OrderTracking.overdue("CANCELLED", TODAY.minusDays(5), TODAY)).isFalse();
    }

    @Test
    void shortClosedIsAReceivedOrderWithMissingUnits() {
        assertThat(OrderTracking.shortClosed("RECEIVED", true)).isTrue();
        assertThat(OrderTracking.shortClosed("RECEIVED", false)).isFalse();
        assertThat(OrderTracking.shortClosed("PARTIALLY_RECEIVED", true)).isFalse();
        assertThat(OrderTracking.shortClosed("CANCELLED", true)).isFalse();
    }

    @Test
    void scopesCoverEveryStatusOnce() {
        assertThat(Scope.DRAFTS.statuses()).containsExactly("DRAFT");
        assertThat(Scope.OPEN.statuses()).containsExactly("SENT", "PARTIALLY_RECEIVED");
        assertThat(Scope.OVERDUE.statuses()).isEqualTo(Scope.OPEN.statuses());
        assertThat(Scope.CLOSED.statuses()).containsExactly("RECEIVED", "CANCELLED");
        assertThat(Scope.ALL.statuses()).containsExactlyInAnyOrder("DRAFT", "SENT", "PARTIALLY_RECEIVED",
                "RECEIVED", "CANCELLED");
        assertThat(Scope.OPEN.byExpectedDate()).isTrue();
        assertThat(Scope.ALL.byExpectedDate()).isFalse();
    }
}
