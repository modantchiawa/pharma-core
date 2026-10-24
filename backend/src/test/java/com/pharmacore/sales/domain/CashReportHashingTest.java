package com.pharmacore.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CashReportHashingTest {

    private static final UUID SESSION = UUID.fromString("7f1c4a6e-2b5d-4c8e-9a01-123456789abc");
    private static final Instant CLOSED = Instant.parse("2026-10-24T19:30:00Z");

    @Test
    void hashIsDeterministicAndHexEncoded() {
        String first = CashReportHashing.hash(SESSION, "T-1", 10_000, 85_000, 92_500, 92_000, CLOSED, null);
        String again = CashReportHashing.hash(SESSION, "T-1", 10_000, 85_000, 92_500, 92_000, CLOSED, CashReportHashing.GENESIS);
        assertThat(first).hasSize(64).matches("[0-9a-f]+").isEqualTo(again);
    }

    @Test
    void anyAlteredFigureBreaksTheChain() {
        String reference = CashReportHashing.hash(SESSION, "T-1", 10_000, 85_000, 92_500, 92_000, CLOSED, "abc");
        assertThat(CashReportHashing.hash(SESSION, "T-1", 10_000, 85_000, 92_500, 92_100, CLOSED, "abc")).isNotEqualTo(reference);
        assertThat(CashReportHashing.hash(SESSION, "T-1", 10_000, 85_000, 92_500, 92_000, CLOSED, "abd")).isNotEqualTo(reference);
        assertThat(CashReportHashing.hash(SESSION, "T-2", 10_000, 85_000, 92_500, 92_000, CLOSED, "abc")).isNotEqualTo(reference);
        assertThat(CashReportHashing.verify(reference, SESSION, "T-1", 10_000, 85_000, 92_500, 92_000, CLOSED, "abc")).isTrue();
        assertThat(CashReportHashing.verify(reference, SESSION, "T-1", 10_000, 85_000, 92_500, 92_000, CLOSED, "zzz")).isFalse();
        assertThat(CashReportHashing.verify(null, SESSION, "T-1", 10_000, 85_000, 92_500, 92_000, CLOSED, "abc")).isFalse();
    }
}
