package com.pharmacore.catalog.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {

    Optional<ProductEntity> findByCip13(String cip13);

    @Query("SELECT p FROM ProductEntity p WHERE p.cip13 = :code OR p.ean13 = :code ORDER BY CASE WHEN p.cip13 = :code THEN 0 ELSE 1 END")
    List<ProductEntity> findByAnyCode(String code);

    List<ProductEntity> findByIdIn(Collection<UUID> ids);

    @Query(value = """
            SELECT * FROM product
            WHERE active AND (cip13 = :term OR ean13 = :term
                              OR designation ILIKE '%' || :term || '%'
                              OR dci ILIKE '%' || :term || '%')
            ORDER BY similarity(designation, :term) DESC, designation
            """, nativeQuery = true)
    List<ProductEntity> search(String term, Pageable pageable);
}
