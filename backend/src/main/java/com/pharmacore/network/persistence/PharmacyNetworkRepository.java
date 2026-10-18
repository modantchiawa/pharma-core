package com.pharmacore.network.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PharmacyNetworkRepository extends JpaRepository<PharmacyNetworkEntity, UUID> {
}
