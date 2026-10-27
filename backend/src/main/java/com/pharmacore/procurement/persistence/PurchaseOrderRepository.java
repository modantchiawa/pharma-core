package com.pharmacore.procurement.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, UUID> {

    @Query("select distinct o from PurchaseOrderEntity o left join fetch o.lines where o.id = :id")
    Optional<PurchaseOrderEntity> findWithLines(UUID id);

    @Query("""
            select distinct o from PurchaseOrderEntity o left join fetch o.lines
            where o.status = :status and o.supplierId in :supplierIds order by o.createdAt desc""")
    List<PurchaseOrderEntity> findWithLinesBySupplier(PurchaseOrderEntity.Status status, Collection<UUID> supplierIds);
}
