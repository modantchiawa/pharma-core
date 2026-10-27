package com.pharmacore.control.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutboxEntity, UUID> {

    /** Due deliveries, locked; rows taken by a concurrent dispatcher are skipped. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from WebhookOutboxEntity o where o.status = 'PENDING' and o.nextAttemptAt <= :now "
            + "order by o.createdAt")
    List<WebhookOutboxEntity> lockDue(Instant now, Pageable pageable);

    @Query("select o from WebhookOutboxEntity o order by o.createdAt desc")
    List<WebhookOutboxEntity> findRecent(Pageable pageable);
}
