package com.pharmacore.messaging.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Network-wide channel or private conversation between two pharmacies of the same network. */
@Entity
@Table(name = "conversation")
public class ConversationEntity {

    public enum Kind {
        NETWORK_CHANNEL,
        DIRECT
    }

    @Id
    private UUID id;

    @Column(name = "network_id", nullable = false, updatable = false)
    private UUID networkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(name = "participant_a_tenant", updatable = false)
    private UUID participantA;

    @Column(name = "participant_b_tenant", updatable = false)
    private UUID participantB;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationEntity() {
    }

    private ConversationEntity(UUID networkId, Kind kind, String title, UUID participantA, UUID participantB,
                               Instant now) {
        this.id = UUID.randomUUID();
        this.networkId = networkId;
        this.kind = kind;
        this.title = title;
        this.participantA = participantA;
        this.participantB = participantB;
        this.createdAt = now;
    }

    public static ConversationEntity channel(UUID networkId, String title, Instant now) {
        return new ConversationEntity(networkId, Kind.NETWORK_CHANNEL, title, null, null, now);
    }

    /** Participants are stored ordered so that a pair maps to a single conversation (unique index). */
    public static ConversationEntity direct(UUID networkId, UUID tenantA, UUID tenantB, String title, Instant now) {
        boolean ordered = tenantA.compareTo(tenantB) < 0;
        return new ConversationEntity(networkId, Kind.DIRECT, title, ordered ? tenantA : tenantB,
                ordered ? tenantB : tenantA, now);
    }

    public UUID id() {
        return id;
    }

    public UUID networkId() {
        return networkId;
    }

    public Kind kind() {
        return kind;
    }

    public String title() {
        return title;
    }

    public UUID participantA() {
        return participantA;
    }

    public UUID participantB() {
        return participantB;
    }
}
