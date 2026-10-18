package com.pharmacore.messaging.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    @Query("""
            select m from ChatMessageEntity m
            where m.conversationId = :conversationId and m.sentAt < :before
            order by m.sentAt desc""")
    List<ChatMessageEntity> findPage(UUID conversationId, Instant before, Pageable pageable);
}
