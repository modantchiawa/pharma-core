package com.pharmacore.inventory.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface StockRepository extends JpaRepository<StockEntity, UUID> {

    Optional<StockEntity> findByProductId(UUID productId);

    List<StockEntity> findByProductIdIn(Collection<UUID> productIds);

    /** Rows are locked in a deterministic order so that concurrent multi-line sales cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockEntity s where s.productId in :productIds order by s.productId")
    List<StockEntity> lockByProductIds(Collection<UUID> productIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockEntity s where s.id = :id")
    Optional<StockEntity> lockById(UUID id);
}
