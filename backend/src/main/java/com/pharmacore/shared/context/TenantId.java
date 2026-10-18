package com.pharmacore.shared.context;

import java.util.Objects;
import java.util.UUID;

/** Strongly typed identifier of a pharmacy (tenant). */
public record TenantId(UUID value) {

    /** Owner of platform-level audit events. */
    public static final TenantId PLATFORM = new TenantId(new UUID(0L, 0L));

    public TenantId {
        Objects.requireNonNull(value, "tenant id");
    }

    public static TenantId of(String value) {
        return new TenantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
