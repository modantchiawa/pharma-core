package com.pharmacore.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pharmacore.procurement.domain.OrderGrouping.Candidate;
import com.pharmacore.procurement.domain.OrderGrouping.Group;
import com.pharmacore.procurement.domain.OrderGrouping.Line;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderGroupingTest {

    private static final UUID LABOREX = UUID.randomUUID();
    private static final UUID COPHARNI = UUID.randomUUID();

    @Test
    void oneGroupPerSupplierInProposalOrderWithEstimatedAmounts() {
        UUID paracetamol = UUID.randomUUID();
        UUID amoxicillin = UUID.randomUUID();
        UUID coartem = UUID.randomUUID();
        var result = OrderGrouping.group(List.of(
                new Candidate(amoxicillin, COPHARNI, 12, 1_260L, "sous le seuil"),
                new Candidate(paracetamol, LABOREX, 20, 470L, "sous la couverture"),
                new Candidate(coartem, COPHARNI, 6, 1_870L, "ventes perdues")), Map.of());

        assertThat(result.groups()).extracting(Group::supplierId).containsExactly(COPHARNI, LABOREX);
        Group copharni = result.groups().getFirst();
        assertThat(copharni.lines()).extracting(Line::productId).containsExactly(amoxicillin, coartem);
        assertThat(copharni.estimatedCents()).isEqualTo(12 * 1_260 + 6 * 1_870);
        assertThat(result.groups().getLast().estimatedCents()).isEqualTo(20 * 470);
        assertThat(result.unassigned()).isEmpty();
    }

    @Test
    void overridesReplaceTheSuggestionAndZeroDropsTheLine() {
        UUID kept = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        var result = OrderGrouping.group(List.of(
                new Candidate(kept, LABOREX, 20, 470L, null),
                new Candidate(dropped, LABOREX, 10, 900L, null)), Map.of(kept, 35, dropped, 0));

        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.lines()).singleElement().satisfies(line -> {
                assertThat(line.productId()).isEqualTo(kept);
                assertThat(line.quantity()).isEqualTo(35);
                assertThat(line.suggestedQuantity()).isEqualTo(20);
            });
            assertThat(group.estimatedCents()).isEqualTo(35 * 470);
        });
    }

    @Test
    void supplierWhoseLinesAreAllExcludedGetsNoGroup() {
        UUID product = UUID.randomUUID();
        var result = OrderGrouping.group(List.of(new Candidate(product, LABOREX, 20, 470L, null)),
                Map.of(product, 0));

        assertThat(result.groups()).isEmpty();
    }

    @Test
    void productsWithoutSupplierAreSetAsideUnlessExcluded() {
        UUID orphan = UUID.randomUUID();
        UUID excludedOrphan = UUID.randomUUID();
        UUID routed = UUID.randomUUID();
        var result = OrderGrouping.group(List.of(
                new Candidate(orphan, null, 5, null, null),
                new Candidate(excludedOrphan, null, 5, null, null),
                new Candidate(routed, COPHARNI, 4, null, null)), Map.of(excludedOrphan, 0));

        assertThat(result.unassigned()).extracting(Candidate::productId).containsExactly(orphan);
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.supplierId()).isEqualTo(COPHARNI);
            // no known purchase price: the line counts for nothing in the estimate
            assertThat(group.estimatedCents()).isZero();
        });
    }

    @Test
    void negativeOverrideIsRejected() {
        UUID product = UUID.randomUUID();
        assertThatThrownBy(() -> OrderGrouping.group(List.of(new Candidate(product, LABOREX, 2, 10L, null)),
                Map.of(product, -1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
