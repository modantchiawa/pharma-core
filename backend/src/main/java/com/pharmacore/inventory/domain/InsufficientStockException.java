package com.pharmacore.inventory.domain;

import java.util.UUID;

public class InsufficientStockException extends RuntimeException {

    private final UUID productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(UUID productId, int requested, int available) {
        super("Insufficient stock for product " + productId + ": requested " + requested + ", available " + available);
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public UUID productId() {
        return productId;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
