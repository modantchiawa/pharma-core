package com.pharmacore.messaging.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "chat_message")
public class ChatMessageEntity {

    public enum Kind {
        TEXT,
        STOCK_SHORTAGE,
        HEALTH_ALERT,
        TRANSFER
    }

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_tenant_id", nullable = false)
    private UUID senderTenantId;

    @Column(name = "sender_user_id", nullable = false)
    private UUID senderUserId;

    @Column(name = "sender_display_name", nullable = false, length = 150)
    private String senderDisplayName;

    @Column(name = "sender_pharmacy_name", nullable = false, length = 150)
    private String senderPharmacyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(name = "related_product_id")
    private UUID relatedProductId;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(UUID conversationId, UUID senderTenantId, UUID senderUserId, String senderDisplayName,
                             String senderPharmacyName, Kind kind, String body, UUID relatedProductId, Instant sentAt) {
        this.id = UUID.randomUUID();
        this.conversationId = conversationId;
        this.senderTenantId = senderTenantId;
        this.senderUserId = senderUserId;
        this.senderDisplayName = senderDisplayName;
        this.senderPharmacyName = senderPharmacyName;
        this.kind = kind;
        this.body = body;
        this.relatedProductId = relatedProductId;
        this.sentAt = sentAt;
    }

    public UUID id() {
        return id;
    }

    public UUID conversationId() {
        return conversationId;
    }

    public UUID senderTenantId() {
        return senderTenantId;
    }

    public UUID senderUserId() {
        return senderUserId;
    }

    public String senderDisplayName() {
        return senderDisplayName;
    }

    public String senderPharmacyName() {
        return senderPharmacyName;
    }

    public Kind kind() {
        return kind;
    }

    public String body() {
        return body;
    }

    public UUID relatedProductId() {
        return relatedProductId;
    }

    public Instant sentAt() {
        return sentAt;
    }
}
