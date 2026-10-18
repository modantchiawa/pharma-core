package com.pharmacore.guard.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GuardScheduleRepository extends JpaRepository<GuardScheduleEntity, UUID> {

    /** Slots overlapping [from, to) in the networks visible to the current pharmacy (RLS). */
    @Query("""
            select g from GuardScheduleEntity g
            where (:networkId is null or g.networkId = :networkId)
              and g.startsAt < :to and g.endsAt > :from
            order by g.startsAt""")
    List<GuardScheduleEntity> findOverlapping(UUID networkId, Instant from, Instant to);
}
