package com.pharmacore.inventory.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockLotRepository extends JpaRepository<StockLotEntity, UUID> {

    @Query("select l from StockLotEntity l where l.stockId in :stockIds and l.quantity > 0 order by l.expiryDate, l.id")
    List<StockLotEntity> findAvailableByStockIds(Collection<UUID> stockIds);

    List<StockLotEntity> findByIdIn(Collection<UUID> ids);

    Optional<StockLotEntity> findByStockIdAndLotNumberAndExpiryDate(UUID stockId, String lotNumber, LocalDate expiryDate);
}
