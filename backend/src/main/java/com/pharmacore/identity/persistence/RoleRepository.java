package com.pharmacore.identity.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<RoleEntity, UUID> {

    @Query("select distinct r from RoleEntity r left join fetch r.permissions order by r.code")
    List<RoleEntity> findAllWithPermissions();

    @Query("select r from RoleEntity r left join fetch r.permissions where r.id = :id")
    Optional<RoleEntity> findWithPermissions(UUID id);

    Optional<RoleEntity> findByCode(String code);

    List<RoleEntity> findByIdIn(Collection<UUID> ids);

    boolean existsByCode(String code);
}
