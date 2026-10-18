package com.pharmacore.inventory.domain;

import java.util.List;

/** Lots to draw from, plus the units that could not be covered by any sellable lot. */
public record AllocationPlan(List<LotAllocation> allocations, int shortfall) {

    public AllocationPlan {
        allocations = List.copyOf(allocations);
        if (shortfall < 0) {
            throw new IllegalArgumentException("Shortfall cannot be negative");
        }
    }

    public int allocatedQuantity() {
        return allocations.stream().mapToInt(LotAllocation::quantity).sum();
    }

    public boolean complete() {
        return shortfall == 0;
    }
}
