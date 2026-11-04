package com.pharmacore.customerorder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerOrderAllocationTest {

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();

    @Test
    void servesTheOldestOrdersInFull() {
        var waiting = List.of(new CustomerOrderAllocation.Waiting(first, 2),
                new CustomerOrderAllocation.Waiting(second, 3), new CustomerOrderAllocation.Waiting(third, 1));
        assertThat(CustomerOrderAllocation.served(waiting, 6)).containsExactly(first, second, third);
        assertThat(CustomerOrderAllocation.served(waiting, 5)).containsExactly(first, second);
    }

    @Test
    void aLargeOrderDoesNotBlockSmallerOnesBehindIt() {
        var waiting = List.of(new CustomerOrderAllocation.Waiting(first, 10),
                new CustomerOrderAllocation.Waiting(second, 2), new CustomerOrderAllocation.Waiting(third, 1));
        assertThat(CustomerOrderAllocation.served(waiting, 3)).containsExactly(second, third);
    }

    @Test
    void nothingAvailableServesNothing() {
        var waiting = List.of(new CustomerOrderAllocation.Waiting(first, 1));
        assertThat(CustomerOrderAllocation.served(waiting, 0)).isEmpty();
        assertThat(CustomerOrderAllocation.served(waiting, -4)).isEmpty();
        assertThatThrownBy(() -> new CustomerOrderAllocation.Waiting(first, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
