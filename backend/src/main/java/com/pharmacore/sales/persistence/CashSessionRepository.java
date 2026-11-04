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

    /** Last Z report of the terminal: the predecessor in the hash chain. */
    @Query("""
            select s from CashSessionEntity s
            where s.terminalId = :terminalId and s.status = com.pharmacore.sales.persistence.CashSessionEntity.Status.CLOSED
            order by s.closedAt desc""")
    List<CashSessionEntity> findClosedByTerminal(String terminalId, Pageable pageable);

    /** Sessions of the terminal that were open at {@code at}: an offline sale must fall inside one. */
    @Query("""
            select count(s) > 0 from CashSessionEntity s
            where s.terminalId = :terminalId and s.openedAt <= :at and (s.closedAt is null or s.closedAt >= :at)""")
    boolean existsCovering(String terminalId, java.time.Instant at);

    @Query("""
            select s from CashSessionEntity s
            where s.closingRequiresApproval = true and s.approvedAt is null
            order by s.closedAt""")
    List<CashSessionEntity> findPendingApproval(Pageable pageable);
}
