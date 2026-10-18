package com.pharmacore.network.domain;

import java.util.UUID;

/** Published after commit so both pharmacies get a real-time notification. */
public record TransferChanged(UUID transferId, UUID requesterTenantId, UUID providerTenantId, UUID productId,
                              int quantity, TransferStatus status) {
}
