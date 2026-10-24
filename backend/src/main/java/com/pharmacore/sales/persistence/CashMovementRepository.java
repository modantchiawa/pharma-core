package com.pharmacore.sales.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashMovementRepository extends JpaRepository<CashMovementEntity, UUID> {

    List<CashMovementEntity> findByCashSessionIdOrderByOccurredAt(UUID cashSessionId);
}
