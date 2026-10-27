package com.pharmacore.inventory.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImageEntity, UUID> {

    Optional<ProductImageEntity> findByProductId(UUID productId);
}
