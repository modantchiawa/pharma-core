package com.pharmacore.procurement.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierProductRepository extends JpaRepository<SupplierProductEntity, UUID> {

    Optional<SupplierProductEntity> findBySupplierIdAndProductId(UUID supplierId, UUID productId);

    List<SupplierProductEntity> findBySupplierId(UUID supplierId);

    List<SupplierProductEntity> findByProductId(UUID productId);
}
