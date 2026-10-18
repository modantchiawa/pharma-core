package com.pharmacore.tenant.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PharmacyConfigRepository extends JpaRepository<PharmacyConfigEntity, UUID> {

    /** At most one row is visible per tenant (unique tenant_id + Hibernate tenant filter + RLS). */
    @Query("select c from PharmacyConfigEntity c")
    Optional<PharmacyConfigEntity> findCurrent();
}
