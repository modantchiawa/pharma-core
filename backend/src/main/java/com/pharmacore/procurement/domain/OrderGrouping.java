package com.pharmacore.procurement.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the order proposal into one order per supplier. Each product goes to the supplier the
 * proposal routed it to (its preferred supplier); the pharmacist's quantity overrides replace
 * the suggestion, an override of zero drops the line. Products without any supplier are set
 * aside so the pharmacist can link them. Pure function: no I/O.
 */
public final class OrderGrouping {

    private OrderGrouping() {
    }

    /** A proposal line: {@code supplierId} is null when no supplier sells the product yet. */
    public record Candidate(UUID productId, UUID supplierId, int suggestedQuantity, Long unitCostCents,
                            String reason) {
    }

    public record Line(UUID productId, int quantity, Long unitCostCents, int suggestedQuantity, String reason) {

        public long estimatedCents() {
            return unitCostCents == null ? 0 : unitCostCents * quantity;
        }
    }

    public record Group(UUID supplierId, List<Line> lines) {

        public long estimatedCents() {
            return lines.stream().mapToLong(Line::estimatedCents).sum();
        }
    }

    /** Groups in the order their supplier first appears in the proposal. */
    public record Result(List<Group> groups, List<Candidate> unassigned) {
    }

    public static Result group(List<Candidate> candidates, Map<UUID, Integer> overrides) {
        Map<UUID, List<Line>> bySupplier = new LinkedHashMap<>();
        List<Candidate> unassigned = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int quantity = overrides.getOrDefault(candidate.productId(), candidate.suggestedQuantity());
            if (quantity < 0) {
                throw new IllegalArgumentException("Negative quantity for product " + candidate.productId());
            }
            if (quantity == 0) {
                continue;
            }
            if (candidate.supplierId() == null) {
                unassigned.add(candidate);
                continue;
            }
            bySupplier.computeIfAbsent(candidate.supplierId(), id -> new ArrayList<>())
                    .add(new Line(candidate.productId(), quantity, candidate.unitCostCents(),
                            candidate.suggestedQuantity(), candidate.reason()));
        }
        List<Group> groups = bySupplier.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new Result(groups, List.copyOf(unassigned));
    }
}
