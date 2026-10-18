package com.pharmacore.inventory.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovementEntity, UUID> {
}
