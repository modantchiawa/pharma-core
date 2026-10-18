package com.pharmacore.network.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of an inter-pharmacy reservation:
 * REQUESTED -> ACCEPTED -> COMPLETED, with DECLINED / CANCELLED / EXPIRED as terminal exits.
 */
public enum TransferStatus {
    REQUESTED,
    ACCEPTED,
    DECLINED,
    CANCELLED,
    EXPIRED,
    COMPLETED;

    public Set<TransferStatus> successors() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(ACCEPTED, DECLINED, CANCELLED);
            case ACCEPTED -> EnumSet.of(COMPLETED, CANCELLED, EXPIRED);
            case DECLINED, CANCELLED, EXPIRED, COMPLETED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean canMoveTo(TransferStatus target) {
        return successors().contains(target);
    }
}
