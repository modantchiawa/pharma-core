package com.pharmacore.customerorder.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Which waiting orders a delivery serves. Oldest first, each order served in full or not at all
 * (half a treatment helps no one); an order too large for what is left does not block the
 * smaller ones behind it. Pure function.
 */
public final class CustomerOrderAllocation {

    private CustomerOrderAllocation() {
    }

    /** An order waiting for the product, in arrival order. */
    public record Waiting(UUID orderId, int quantity) {

        public Waiting {
            if (quantity <= 0) {
                throw new IllegalArgumentException("A waiting order needs a positive quantity");
            }
        }
    }

    /** @param available units on the shelf that nothing else holds */
    public static List<UUID> served(List<Waiting> waiting, int available) {
        List<UUID> served = new ArrayList<>();
        int left = Math.max(available, 0);
        for (Waiting order : waiting) {
            if (order.quantity() <= left) {
                served.add(order.orderId());
                left -= order.quantity();
            }
        }
        return served;
    }
}
