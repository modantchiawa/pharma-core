package com.pharmacore.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransferStatusTest {

    @Test
    void followsReservationLifecycle() {
        assertThat(TransferStatus.REQUESTED.canMoveTo(TransferStatus.ACCEPTED)).isTrue();
        assertThat(TransferStatus.ACCEPTED.canMoveTo(TransferStatus.COMPLETED)).isTrue();
        assertThat(TransferStatus.ACCEPTED.canMoveTo(TransferStatus.EXPIRED)).isTrue();
        assertThat(TransferStatus.REQUESTED.canMoveTo(TransferStatus.COMPLETED)).isFalse();
        assertThat(TransferStatus.COMPLETED.successors()).isEmpty();
        assertThat(TransferStatus.DECLINED.canMoveTo(TransferStatus.ACCEPTED)).isFalse();
    }

    @Test
    void availabilityIsCoarse() {
        assertThat(AvailabilityLevel.of(0)).isEqualTo(AvailabilityLevel.OUT_OF_STOCK);
        assertThat(AvailabilityLevel.of(2)).isEqualTo(AvailabilityLevel.LOW);
        assertThat(AvailabilityLevel.of(10)).isEqualTo(AvailabilityLevel.AVAILABLE);
    }
}
