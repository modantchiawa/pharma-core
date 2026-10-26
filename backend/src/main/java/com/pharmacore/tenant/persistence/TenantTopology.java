package com.pharmacore.tenant.persistence;

/** Where a pharmacy's operational data is mastered. */
public enum TenantTopology {
    /** Directly on the cloud (PWA with offline journal). */
    CLOUD,
    /** On a local hub in the pharmacy, replicated to the cloud. */
    HUB
}
