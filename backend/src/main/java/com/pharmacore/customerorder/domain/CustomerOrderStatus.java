package com.pharmacore.customerorder.domain;

/** Life of a customer order: waiting for the product, reserved on the shelf, then handed over or given up. */
public enum CustomerOrderStatus {
    OPEN,
    READY,
    DELIVERED,
    CANCELLED;

    /** Still owed to the customer. */
    public boolean pending() {
        return this == OPEN || this == READY;
    }
}
