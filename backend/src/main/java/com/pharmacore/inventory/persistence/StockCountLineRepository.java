package com.pharmacore.inventory.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockCountLineRepository extends JpaRepository<StockCountLineEntity, UUID> {

    @Query("select l from StockCountLineEntity l where l.countId = :countId order by l.lotNumber, l.expiryDate")
    List<StockCountLineEntity> findByCount(UUID countId);

    @Query("select l from StockCountLineEntity l where l.countId = :countId and l.id = :lineId")
    Optional<StockCountLineEntity> findLine(UUID countId, UUID lineId);
}
