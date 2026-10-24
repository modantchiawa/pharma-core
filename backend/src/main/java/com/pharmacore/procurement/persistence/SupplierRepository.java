package com.pharmacore.procurement.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SupplierRepository extends JpaRepository<SupplierEntity, UUID> {

    @Query("select s from SupplierEntity s where s.active = true order by s.name")
    List<SupplierEntity> findActive();

    boolean existsByCode(String code);
}
