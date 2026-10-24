package com.pharmacore.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DenominationsTest {

    @Test
    void totalAddsEveryNoteAndCoin() {
        long total = Denominations.total(List.of(
                new Denominations.Line(10_000, 3),
                new Denominations.Line(500, 4),
                new Denominations.Line(25, 2),
                new Denominations.Line(5, 0)));
        assertThat(total).isEqualTo(30_000 + 2_000 + 50);
    }

    @Test
    void emptyCountIsZeroAndDuplicatesAddUp() {
        assertThat(Denominations.total(List.of())).isZero();
        assertThat(Denominations.total(List.of(new Denominations.Line(1_000, 1), new Denominations.Line(1_000, 2))))
                .isEqualTo(3_000);
    }

    @Test
    void invalidLinesAreRefused() {
        assertThatThrownBy(() -> new Denominations.Line(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Denominations.Line(500, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void xofSeriesIsSortedFromLargestToSmallest() {
        assertThat(Denominations.XOF).isSortedAccordingTo(java.util.Comparator.reverseOrder())
                .contains(10_000L, 5L);
    }
}
