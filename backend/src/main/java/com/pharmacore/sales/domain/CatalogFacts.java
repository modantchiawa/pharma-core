package com.pharmacore.sales.domain;

import com.pharmacore.catalog.domain.DrugListing;
import java.util.UUID;

/** What the server knows about a product at replay time. */
public record CatalogFacts(UUID productId, long referencePriceCents, DrugListing listing, boolean active) {
}
