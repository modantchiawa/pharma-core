package com.pharmacore.insurance.persistence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InsurancePolicyRepository extends JpaRepository<InsurancePolicyEntity, UUID> {

    @Query("select p from InsurancePolicyEntity p where p.insurerId = :insurerId order by p.active desc, p.beneficiaryName")
    List<InsurancePolicyEntity> findByInsurer(UUID insurerId);

    @Query("""
            select p from InsurancePolicyEntity p
            where p.customerId = :customerId and p.active = true
              and p.validFrom <= :today and (p.validUntil is null or p.validUntil >= :today)
            order by p.coverageRate desc""")
    List<InsurancePolicyEntity> findUsableByCustomer(UUID customerId, LocalDate today);

    List<InsurancePolicyEntity> findByIdIn(Collection<UUID> ids);
}
