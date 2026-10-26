package com.pharmacore.inventory.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockCountRepository extends JpaRepository<StockCountEntity, UUID> {

    @Query("select c from StockCountEntity c order by c.startedAt desc")
    List<StockCountEntity> findRecent(Pageable pageable);
}
