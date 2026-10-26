package com.pharmacore.insurance.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InsurerRepository extends JpaRepository<InsurerEntity, UUID> {

    @Query("select i from InsurerEntity i order by i.active desc, i.name")
    List<InsurerEntity> findAllOrdered();

    @Query("select i from InsurerEntity i where i.active = true order by i.name")
    List<InsurerEntity> findActive();
}
