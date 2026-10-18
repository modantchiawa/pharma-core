package com.pharmacore.messaging.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Visibility of conversations is enforced by RLS (network membership and participants). */
public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    @Query("select c from ConversationEntity c order by c.kind, c.title")
    List<ConversationEntity> findVisible();

    @Query("""
            select c from ConversationEntity c
            where c.networkId = :networkId
              and c.kind = com.pharmacore.messaging.persistence.ConversationEntity.Kind.DIRECT
              and c.participantA = :a and c.participantB = :b""")
    Optional<ConversationEntity> findDirect(UUID networkId, UUID a, UUID b);
}
