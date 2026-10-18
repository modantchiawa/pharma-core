package com.pharmacore.sales.domain;

import java.util.Set;
import java.util.UUID;

/** Per-sale result returned to the terminal; drives what it does with its local journal entry. */
public sealed interface SaleOutcome {

    UUID clientSaleId();

    /** Stored: the terminal marks the entry as synced. */
    record Accepted(UUID clientSaleId, UUID saleId, Long ordonnancierNumber, Set<SaleAnomaly> anomalies)
            implements SaleOutcome {
    }

    /** Already stored by a previous attempt: the terminal marks the entry as synced. */
    record Duplicate(UUID clientSaleId, UUID saleId) implements SaleOutcome {
    }

    /** Refused for good (online rule violation or malformed): the terminal must surface it. */
    record Rejected(UUID clientSaleId, String code, Set<SaleAnomaly> violations, String message)
            implements SaleOutcome {
    }

    /** Transient failure: the terminal keeps the entry and retries with backoff. */
    record Retry(UUID clientSaleId, String message) implements SaleOutcome {
    }
}
