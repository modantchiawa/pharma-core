package com.pharmacore.sales.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CashSessionRepository extends JpaRepository<CashSessionEntity, UUID> {

    @Query("select s from CashSessionEntity s where s.terminalId = :terminalId and s.status = com.pharmacore.sales.persistence.CashSessionEntity.Status.OPEN")
    Optional<CashSessionEntity> findOpen(String terminalId);

    @Query("select s from CashSessionEntity s order by s.openedAt desc")
    List<CashSessionEntity> findRecent(Pageable pageable);
}
