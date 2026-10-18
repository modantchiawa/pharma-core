package com.pharmacore.network.domain;

/** Coarse availability exposed to partner pharmacies instead of exact stock figures. */
public enum AvailabilityLevel {
    OUT_OF_STOCK,
    LOW,
    AVAILABLE;

    static final int LOW_THRESHOLD = 3;

    public static AvailabilityLevel of(int quantity) {
        if (quantity <= 0) {
            return OUT_OF_STOCK;
        }
        return quantity < LOW_THRESHOLD ? LOW : AVAILABLE;
    }
}
