package com.pharmacore.network.domain;

/**
 * The only stock information the general public ever sees: a pharmacy either has enough of a
 * product or is running low. No quantity, no price, no cost.
 */
public enum PublicAvailabilityLevel {
    IN_STOCK,
    LOW;

    public static PublicAvailabilityLevel parse(String value) {
        return switch (value) {
            case "IN_STOCK" -> IN_STOCK;
            case "LOW" -> LOW;
            default -> throw new IllegalArgumentException("Unknown public availability level " + value);
        };
    }
}
