package com.pharmacore.prescription.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PrescriptionRepository extends JpaRepository<PrescriptionEntity, UUID> {

    @Query("select distinct p from PrescriptionEntity p left join fetch p.lines where p.id = :id")
    Optional<PrescriptionEntity> findWithLines(UUID id);

    @Query("select distinct p from PrescriptionEntity p left join fetch p.lines where p.id in :ids")
    List<PrescriptionEntity> findAllWithLines(Collection<UUID> ids);
}
