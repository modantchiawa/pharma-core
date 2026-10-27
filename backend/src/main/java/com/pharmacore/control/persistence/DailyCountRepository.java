package com.pharmacore.control.persistence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DailyCountRepository extends JpaRepository<DailyCountEntity, UUID> {

    Optional<DailyCountEntity> findByCountDate(LocalDate countDate);

    /** Taken when starting the count: two clicks on "Démarrer" open a single count. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DailyCountEntity d where d.countDate = :countDate")
    Optional<DailyCountEntity> lockByCountDate(LocalDate countDate);
}
