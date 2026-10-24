package com.pharmacore.sales.domain;

import java.util.List;

/** Cash counted by banknote and coin: the drawer total is derived, never typed. */
public final class Denominations {

    /** XOF notes and coins in circulation in the UEMOA zone, in minor units (XOF has none). */
    public static final List<Long> XOF = List.of(10_000L, 5_000L, 2_000L, 1_000L, 500L, 250L, 200L, 100L, 50L, 25L,
            10L, 5L);

    public record Line(long valueCents, int count) {

        public Line {
            if (valueCents <= 0) {
                throw new IllegalArgumentException("A denomination must be positive: " + valueCents);
            }
            if (count < 0) {
                throw new IllegalArgumentException("A denomination count cannot be negative: " + count);
            }
        }

        public long totalCents() {
            return Math.multiplyExact(valueCents, (long) count);
        }
    }

    private Denominations() {
    }

    /** Sum of the counted denominations; duplicates of a value are added up. */
    public static long total(List<Line> lines) {
        long total = 0;
        for (Line line : lines) {
            total = Math.addExact(total, line.totalCents());
        }
        return total;
    }
}
