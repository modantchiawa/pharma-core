package com.pharmacore.control.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ControlAlertRepository extends JpaRepository<ControlAlertEntity, UUID> {

    @Query("select a from ControlAlertEntity a where (:unreadOnly = false or a.acknowledgedAt is null) "
            + "order by a.createdAt desc")
    List<ControlAlertEntity> findFeed(boolean unreadOnly, Pageable pageable);

    @Query("select count(a) from ControlAlertEntity a where a.acknowledgedAt is null")
    long countUnread();

    @Query("select a from ControlAlertEntity a where a.acknowledgedAt is null")
    List<ControlAlertEntity> findUnread();
}
