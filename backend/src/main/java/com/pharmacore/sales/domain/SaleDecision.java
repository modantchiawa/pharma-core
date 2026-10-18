package com.pharmacore.sales.domain;

import java.util.Set;

/** Outcome of the rule evaluation, exhaustively matched by the recording service. */
public sealed interface SaleDecision {

    record Accept(Set<SaleAnomaly> anomalies) implements SaleDecision {

        public Accept {
            anomalies = Set.copyOf(anomalies);
        }

        public boolean reviewRequired() {
            return !anomalies.isEmpty();
        }
    }

    record Reject(Set<SaleAnomaly> violations) implements SaleDecision {

        public Reject {
            violations = Set.copyOf(violations);
        }
    }
}
