package com.pharmacore.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceMatchingTest {

    private final UUID acure = UUID.randomUUID();
    private final UUID apramol1g = UUID.randomUUID();
    private final UUID apramol500 = UUID.randomUUID();
    private final List<InvoiceMatching.Ordered> ordered = List.of(
            new InvoiceMatching.Ordered(acure, "ACURE 20/120 B/6"),
            new InvoiceMatching.Ordered(apramol1g, "APRAMOL 1G CP EFF B/8"),
            new InvoiceMatching.Ordered(apramol500, "APRAMOL 500MG CP EFF 2X8"));

    @Test
    void aWholesalerCodeIsMatchedByTheDesignationOfTheOrder() {
        assertThat(InvoiceMatching.byDesignation("ACURE 20/120 B/6", ordered)).contains(acure);
        // punctuation and case do not matter
        assertThat(InvoiceMatching.byDesignation("apramol 1g cp eff b 8", ordered)).contains(apramol1g);
    }

    @Test
    void aDesignationCompletedByTheCatalogueStillMatches() {
        var withBrand = List.of(new InvoiceMatching.Ordered(acure, "ACURE 20/120 B/6 SANOFI"));
        assertThat(InvoiceMatching.byDesignation("ACURE 20/120 B/6", withBrand)).contains(acure);
    }

    @Test
    void anAmbiguousOrUnknownDesignationIsLeftToThePharmacist() {
        assertThat(InvoiceMatching.byDesignation("APRAMOL", ordered)).isEmpty();
        assertThat(InvoiceMatching.byDesignation("AZIX 500MG CP B/3", ordered)).isEmpty();
        assertThat(InvoiceMatching.byDesignation("  ", ordered)).isEmpty();
    }
}
