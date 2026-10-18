package com.pharmacore.network.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NetworkMembershipRepository
        extends JpaRepository<NetworkMembershipEntity, NetworkMembershipEntity.Key> {

    @Query("""
            select m from NetworkMembershipEntity m
            where m.key.networkId = :networkId and m.key.tenantId = :tenantId
              and m.status = com.pharmacore.network.persistence.NetworkMembershipEntity.Status.ACTIVE""")
    java.util.Optional<NetworkMembershipEntity> findActive(UUID networkId, UUID tenantId);

    @Query("select m from NetworkMembershipEntity m where m.key.tenantId = :tenantId")
    List<NetworkMembershipEntity> findByTenant(UUID tenantId);
}
