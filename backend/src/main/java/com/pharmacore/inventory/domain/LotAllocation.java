package com.pharmacore.inventory.domain;

import java.util.UUID;

public record LotAllocation(UUID lotId, int quantity) {

    public LotAllocation {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Allocated quantity must be positive");
        }
    }
}
