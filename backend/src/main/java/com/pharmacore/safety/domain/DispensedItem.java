package com.pharmacore.safety.domain;

import java.util.List;
import java.util.UUID;

/** A product of the basket, with what the catalog knows of its composition. */
public record DispensedItem(UUID productId, String designation, String dci, String atcCode) {

    public List<String> components() {
        return SubstanceNames.components(dci);
    }

    public boolean matches(String token) {
        return SubstanceNames.matches(token, components(), atcCode);
    }
}
