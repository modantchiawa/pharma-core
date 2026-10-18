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
