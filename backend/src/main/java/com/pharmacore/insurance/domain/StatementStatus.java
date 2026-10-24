package com.pharmacore.insurance.domain;

/** Life cycle of a statement ("bordereau") sent to an insurer. */
public enum StatementStatus {
    /** Built from the period's covered sales, still editable through regeneration. */
    DRAFT,
    /** Sent to the insurer; receivable awaited, partial settlements allowed. */
    ISSUED,
    /** Fully settled. */
    PAID
}
