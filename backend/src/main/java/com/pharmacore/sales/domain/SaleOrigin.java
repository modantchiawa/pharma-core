package com.pharmacore.sales.domain;

public enum SaleOrigin {
    /** Recorded live: business rules are enforced, violations are refused. */
    ONLINE,
    /** Replayed from a terminal's offline journal: the sale already happened, violations are flagged. */
    OFFLINE_REPLAY
}
