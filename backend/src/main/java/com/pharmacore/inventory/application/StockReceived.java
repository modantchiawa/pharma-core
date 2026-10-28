package com.pharmacore.inventory.application;

import java.util.UUID;

/** Units of a product entered the stock (delivery, direct lot entry, import); published in the same transaction. */
public record StockReceived(UUID tenantId, UUID productId, int quantity) {
}
