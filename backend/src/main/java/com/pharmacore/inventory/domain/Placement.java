package com.pharmacore.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a product is stored: a location and, optionally, a finer position inside it ("case 12",
 * "bac 4"). A detail only makes sense within its location: it is dropped when there is no
 * location, and when the product moves to another one.
 */
public record Placement(UUID locationId, String detail) {

    public static final int DETAIL_MAX_LENGTH = 40;

    public Placement {
        detail = locationId == null || detail == null || detail.isBlank() ? null : detail.strip();
        if (detail != null && detail.length() > DETAIL_MAX_LENGTH) {
            throw new IllegalArgumentException("Location detail longer than " + DETAIL_MAX_LENGTH);
        }
    }

    public static Placement none() {
        return new Placement(null, null);
    }

    /** Bulk move: the detail is kept when the product stays where it is, forgotten otherwise. */
    public Placement movedTo(UUID target) {
        return Objects.equals(target, locationId) ? this : new Placement(target, null);
    }
}
