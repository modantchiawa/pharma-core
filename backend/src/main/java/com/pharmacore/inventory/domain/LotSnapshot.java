package com.pharmacore.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

public record LotSnapshot(UUID lotId, String lotNumber, LocalDate expiryDate, int quantity) {
}
