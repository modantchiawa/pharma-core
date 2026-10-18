package com.pharmacore.inventory.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * First Expired, First Out allocation. Lots expiring today or earlier are never sold. Among
 * sellable lots the earliest expiry is consumed first; ties are broken by lot id so that two
 * replicas compute the same plan.
 */
public final class FefoAllocator {

    private static final Comparator<LotSnapshot> FEFO = Comparator
            .comparing(LotSnapshot::expiryDate)
            .thenComparing(LotSnapshot::lotId);

    private FefoAllocator() {
    }

    public static boolean sellable(LotSnapshot lot, LocalDate today) {
        return lot.quantity() > 0 && lot.expiryDate().isAfter(today);
    }

    public static int sellableQuantity(List<LotSnapshot> lots, LocalDate today) {
        return lots.stream().filter(lot -> sellable(lot, today)).mapToInt(LotSnapshot::quantity).sum();
    }

    /**
     * @param reserved units promised to another pharmacy, which must stay on the shelf
     */
    public static AllocationPlan allocate(UUID productId, List<LotSnapshot> lots, int requested, int reserved,
                                          LocalDate today, ShortfallPolicy policy) {
        if (requested <= 0) {
            throw new IllegalArgumentException("Requested quantity must be positive");
        }
        int available = Math.max(sellableQuantity(lots, today) - reserved, 0);
        if (available < requested && policy == ShortfallPolicy.REJECT) {
            throw new InsufficientStockException(productId, requested, available);
        }
        int remaining = Math.min(requested, available);
        List<LotAllocation> allocations = new ArrayList<>();
        for (LotSnapshot lot : lots.stream().filter(l -> sellable(l, today)).sorted(FEFO).toList()) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(lot.quantity(), remaining);
            allocations.add(new LotAllocation(lot.lotId(), take));
            remaining -= take;
        }
        return new AllocationPlan(allocations, requested - Math.min(requested, available));
    }
}
