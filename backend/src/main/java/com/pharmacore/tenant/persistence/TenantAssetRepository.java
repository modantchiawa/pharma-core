package com.pharmacore.tenant.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantAssetRepository extends JpaRepository<TenantAssetEntity, UUID> {
}
