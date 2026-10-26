package com.pharmacore.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    /** Single round-trip load of the user with roles and permissions (tenant filter added by Hibernate). */
    @Query("""
            select distinct u from AppUserEntity u
            left join fetch u.roles r
            left join fetch r.permissions
            where u.externalSubject = :subject""")
    Optional<AppUserEntity> findWithAuthorities(String subject);

    @Query("""
            select distinct u from AppUserEntity u
            left join fetch u.roles r
            left join fetch r.permissions
            where u.id = :id""")
    Optional<AppUserEntity> findWithAuthoritiesById(UUID id);

    @Query("select distinct u from AppUserEntity u left join fetch u.roles order by u.displayName")
    List<AppUserEntity> findAllWithRoles();

    boolean existsByEmailIgnoreCase(String email);

    Optional<AppUserEntity> findByEmailIgnoreCase(String email);
}
