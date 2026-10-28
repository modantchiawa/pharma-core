package com.pharmacore.safety.domain;

import java.util.List;
import java.util.UUID;

/**
 * A finding shown at the counter. {@code key} identifies it stably across the terminal and the
 * server (same rules, same basket, same key) so an override recorded by the pharmacist on the
 * terminal can be matched when the sale is recorded.
 */
public record SafetyAlert(Kind kind, Severity severity, String title, String detail, List<UUID> productIds,
                          String key) {

    public enum Kind {
        INTERACTION,
        DUPLICATE_SUBSTANCE,
        ALLERGY,
        CONTEXT,
        RECENT_DISPENSING
    }

    public SafetyAlert {
        productIds = List.copyOf(productIds);
    }

    public boolean blocking() {
        return severity.blocking();
    }
}
