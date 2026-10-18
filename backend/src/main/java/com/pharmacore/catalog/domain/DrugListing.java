package com.pharmacore.catalog.domain;

import java.util.Optional;

/** Regulatory classification driving prescription requirements and dispensing rights. */
public enum DrugListing {
    NONE,
    LIST_I,
    LIST_II,
    STUPEFIANT;

    public boolean requiresPrescription() {
        return this != NONE;
    }

    /** Listed drugs are recorded in the ordonnancier (sequential dispensing register). */
    public boolean recordedInOrdonnancier() {
        return this != NONE;
    }

    /** Permission needed on top of SALE_CREATE to dispense this product. */
    public Optional<String> requiredPermission() {
        return switch (this) {
            case NONE -> Optional.empty();
            case LIST_I, LIST_II -> Optional.of("PRESCRIPTION_DISPENSE");
            case STUPEFIANT -> Optional.of("NARCOTIC_DISPENSE");
        };
    }
}
