package com.pharmacore.sales.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SaleRepository extends JpaRepository<SaleEntity, UUID> {

    @Query("select s.id from SaleEntity s where s.clientSaleId = :clientSaleId")
    Optional<UUID> findIdByClientSaleId(UUID clientSaleId);

    @Query("""
            select distinct s from SaleEntity s
            left join fetch s.lines l
            where s.id = :id""")
    Optional<SaleEntity> findWithLines(UUID id);

    @Query("""
            select s from SaleEntity s
            where s.occurredAt >= :from and s.occurredAt < :to
            order by s.occurredAt desc""")
    List<SaleEntity> findInPeriod(Instant from, Instant to, Pageable pageable);

    @Query("select s from SaleEntity s where s.reviewRequired = true order by s.occurredAt")
    List<SaleEntity> findReviewQueue(Pageable pageable);
}
