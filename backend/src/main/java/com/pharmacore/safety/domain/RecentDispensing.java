package com.pharmacore.safety.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A product the same customer already received recently. */
public record RecentDispensing(UUID productId, String designation, String dci, String atcCode, LocalDate dispensedOn) {

    public List<String> components() {
        return SubstanceNames.components(dci);
    }
}
