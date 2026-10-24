package com.pharmacore.procurement.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, UUID> {

    @Query("select distinct o from PurchaseOrderEntity o left join fetch o.lines where o.id = :id")
    Optional<PurchaseOrderEntity> findWithLines(UUID id);

    @Query("select o from PurchaseOrderEntity o where (:status is null or o.status = :status) order by o.createdAt desc")
    List<PurchaseOrderEntity> findRecent(PurchaseOrderEntity.Status status, Pageable pageable);
}
