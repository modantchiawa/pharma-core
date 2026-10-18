package com.pharmacore.inventory.domain;

/** What to do when sellable lots cannot cover the requested quantity. */
public enum ShortfallPolicy {
    /** Online sale or transfer: refuse, nothing is decremented. */
    REJECT,
    /**
     * Offline sale replay: the goods already left the pharmacy. Allocate what exists and record
     * the remainder as a shortfall to reconcile at the next inventory, never lose the sale.
     */
    RECORD
}
