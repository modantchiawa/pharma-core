package com.pharmacore.customers.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerEntity c where c.id = :id")
    Optional<CustomerEntity> lockById(UUID id);

    @Query(value = """
            SELECT * FROM customer
            WHERE active AND (code = :term OR phone = :term OR display_name ILIKE '%' || :term || '%')
            ORDER BY display_name
            """, nativeQuery = true)
    List<CustomerEntity> search(String term, Pageable pageable);

    @Query("select c from CustomerEntity c where c.creditBalanceCents > 0 order by c.creditBalanceCents desc")
    List<CustomerEntity> findDebtors(Pageable pageable);

    boolean existsByCode(String code);
}
