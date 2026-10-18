package com.pharmacore.network.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InterPharmacyTransferRepository extends JpaRepository<InterPharmacyTransferEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from InterPharmacyTransferEntity t where t.id = :id")
    Optional<InterPharmacyTransferEntity> lockById(UUID id);

    @Query("""
            select t from InterPharmacyTransferEntity t
            where t.requesterTenantId = :tenant or t.providerTenantId = :tenant
            order by t.requestedAt desc""")
    List<InterPharmacyTransferEntity> findForTenant(UUID tenant, Pageable pageable);

    /** Discovery for the expiry job (cross-tenant context). */
    @Query("""
            select t.id as id, t.providerTenantId as providerTenantId from InterPharmacyTransferEntity t
            where (t.status = com.pharmacore.network.domain.TransferStatus.ACCEPTED and t.reservationExpiresAt < :now)
               or (t.reservationHeld = true and t.status <> com.pharmacore.network.domain.TransferStatus.ACCEPTED)""")
    List<PendingRelease> findReleasable(Instant now, Pageable pageable);

    interface PendingRelease {
        UUID getId();

        UUID getProviderTenantId();
    }
}
