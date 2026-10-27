package com.pharmacore.inventory.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface StorageLocationRepository extends JpaRepository<StorageLocationEntity, UUID> {

    Optional<StorageLocationEntity> findByCode(String code);

    Optional<StorageLocationEntity> findFirstByNameIgnoreCase(String name);

    /** Taken by a deactivation: no product can be put there until it commits. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StorageLocationEntity l where l.id = :id")
    Optional<StorageLocationEntity> lockById(UUID id);

    /** Taken by an assignment: the location cannot be deactivated under it. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select l from StorageLocationEntity l where l.id = :id")
    Optional<StorageLocationEntity> shareById(UUID id);
}
