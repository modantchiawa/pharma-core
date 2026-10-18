package com.pharmacore.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FefoAllocatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final UUID PRODUCT = UUID.randomUUID();

    private final LotSnapshot expired = lot("EXP", TODAY, 50);
    private final LotSnapshot early = lot("EARLY", TODAY.plusDays(10), 3);
    private final LotSnapshot late = lot("LATE", TODAY.plusMonths(6), 20);

    @Test
    void consumesEarliestExpiryFirstAndNeverSellsExpiredLots() {
        var plan = FefoAllocator.allocate(PRODUCT, List.of(late, expired, early), 5, 0, TODAY, ShortfallPolicy.REJECT);

        assertThat(plan.allocations()).containsExactly(
                new LotAllocation(early.lotId(), 3),
                new LotAllocation(late.lotId(), 2));
        assertThat(plan.shortfall()).isZero();
        assertThat(plan.complete()).isTrue();
    }

    @Test
    void lotExpiringTodayIsNotSellable() {
        assertThat(FefoAllocator.sellable(expired, TODAY)).isFalse();
        assertThat(FefoAllocator.sellableQuantity(List.of(expired, early, late), TODAY)).isEqualTo(23);
    }

    @Test
    void reservedUnitsStayOnTheShelf() {
        assertThatThrownBy(() -> FefoAllocator.allocate(PRODUCT, List.of(early, late), 20, 5, TODAY,
                ShortfallPolicy.REJECT))
                .isInstanceOfSatisfying(InsufficientStockException.class, e -> {
                    assertThat(e.requested()).isEqualTo(20);
                    assertThat(e.available()).isEqualTo(18);
                });
    }

    @Test
    void offlineReplayRecordsShortfallInsteadOfFailing() {
        var plan = FefoAllocator.allocate(PRODUCT, List.of(early, expired), 7, 0, TODAY, ShortfallPolicy.RECORD);

        assertThat(plan.allocations()).containsExactly(new LotAllocation(early.lotId(), 3));
        assertThat(plan.shortfall()).isEqualTo(4);
        assertThat(plan.allocatedQuantity()).isEqualTo(3);
        assertThat(plan.complete()).isFalse();
    }

    @Test
    void noStockAtAllIsAFullShortfallOffline() {
        var plan = FefoAllocator.allocate(PRODUCT, List.of(), 2, 0, TODAY, ShortfallPolicy.RECORD);

        assertThat(plan.allocations()).isEmpty();
        assertThat(plan.shortfall()).isEqualTo(2);
    }

    @Test
    void sameExpiryIsOrderedDeterministicallyByLotId() {
        var a = new LotSnapshot(new UUID(0, 1), "A", TODAY.plusDays(30), 1);
        var b = new LotSnapshot(new UUID(0, 2), "B", TODAY.plusDays(30), 1);

        var plan = FefoAllocator.allocate(PRODUCT, List.of(b, a), 1, 0, TODAY, ShortfallPolicy.REJECT);

        assertThat(plan.allocations()).containsExactly(new LotAllocation(a.lotId(), 1));
    }

    @Test
    void rejectsNonPositiveQuantities() {
        assertThatThrownBy(() -> FefoAllocator.allocate(PRODUCT, List.of(late), 0, 0, TODAY, ShortfallPolicy.REJECT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LotSnapshot lot(String number, LocalDate expiry, int quantity) {
        return new LotSnapshot(UUID.randomUUID(), number, expiry, quantity);
    }
}
