package com.pharmacore.messaging.application;

import com.pharmacore.messaging.persistence.ChatMessageEntity;
import com.pharmacore.messaging.persistence.ChatMessageRepository;
import com.pharmacore.messaging.persistence.ConversationEntity;
import com.pharmacore.messaging.persistence.ConversationRepository;
import com.pharmacore.network.application.NetworkDirectoryService;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.PharmacyConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Secure inter-pharmacy messaging. Messages are professional exchanges (shortages, help
 * requests, local health alerts) and must not contain identifying patient data; they are
 * immutable once sent.
 */
@Service
public class ChatService {

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final NetworkDirectoryService directory;
    private final PharmacyConfigRepository configs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChatService(ConversationRepository conversations, ChatMessageRepository messages,
                       NetworkDirectoryService directory, PharmacyConfigRepository configs,
                       ApplicationEventPublisher events, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.directory = directory;
        this.configs = configs;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ConversationView> conversations() {
        return conversations.findVisible().stream().map(ConversationView::of).toList();
    }

    /** RLS makes invisible conversations indistinguishable from missing ones. */
    @Transactional(readOnly = true)
    public boolean canAccess(UUID conversationId) {
        return conversations.findById(conversationId).isPresent();
    }

    @Transactional
    public ConversationView createChannel(UUID networkId, String title) {
        var tenant = TenantContext.requireTenant().value();
        boolean coordinator = directory.directory().stream()
                .anyMatch(e -> e.networkId().equals(networkId) && e.tenantId().equals(tenant) && e.coordinator());
        if (!coordinator) {
            throw new BusinessRuleException("not-coordinator", "Only the network coordinator creates channels");
        }
        return ConversationView.of(conversations.save(ConversationEntity.channel(networkId, title, clock.instant())));
    }

    @Transactional
    public ConversationView openDirect(UUID networkId, UUID otherTenantId) {
        var tenant = TenantContext.requireTenant().value();
        var partner = directory.directory().stream()
                .filter(e -> e.networkId().equals(networkId) && e.tenantId().equals(otherTenantId))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("not-a-partner",
                        "This pharmacy is not an active member of the network"));
        var me = configs.findCurrent().orElseThrow(() -> new NotFoundException("Pharmacy configuration", "current"));
        var candidate = ConversationEntity.direct(networkId, tenant, otherTenantId,
                me.branding().displayName() + " ↔ " + partner.pharmacyName(), clock.instant());
        return ConversationView.of(conversations.findDirect(networkId, candidate.participantA(), candidate.participantB())
                .orElseGet(() -> conversations.save(candidate)));
    }

    @Transactional
    public MessageView post(UUID conversationId, ChatMessageEntity.Kind kind, String body, UUID relatedProductId) {
        if (!canAccess(conversationId)) {
            throw new NotFoundException("Conversation", conversationId);
        }
        var user = TenantContext.requireTenantUser();
        var pharmacy = configs.findCurrent().orElseThrow(() -> new NotFoundException("Pharmacy configuration", "current"));
        var message = messages.save(new ChatMessageEntity(conversationId, user.tenant().value(), user.userId(),
                user.displayName(), pharmacy.branding().displayName(), kind, body.strip(), relatedProductId,
                clock.instant()));
        var view = MessageView.of(message);
        events.publishEvent(new ChatMessagePosted(view));
        return view;
    }

    @Transactional(readOnly = true)
    public List<MessageView> history(UUID conversationId, Instant before, int limit) {
        if (!canAccess(conversationId)) {
            throw new NotFoundException("Conversation", conversationId);
        }
        return messages.findPage(conversationId, before == null ? clock.instant().plusSeconds(1) : before,
                        PageRequest.of(0, Math.clamp(limit, 1, 200)))
                .stream().map(MessageView::of).toList();
    }

    public record ConversationView(UUID id, UUID networkId, String kind, String title, UUID participantA,
                                   UUID participantB) {

        static ConversationView of(ConversationEntity c) {
            return new ConversationView(c.id(), c.networkId(), c.kind().name(), c.title(), c.participantA(),
                    c.participantB());
        }
    }

    public record MessageView(UUID id, UUID conversationId, UUID senderTenantId, UUID senderUserId,
                              String senderDisplayName, String senderPharmacyName, String kind, String body,
                              UUID relatedProductId, Instant sentAt) {

        static MessageView of(ChatMessageEntity m) {
            return new MessageView(m.id(), m.conversationId(), m.senderTenantId(), m.senderUserId(),
                    m.senderDisplayName(), m.senderPharmacyName(), m.kind().name(), m.body(),
                    m.relatedProductId(), m.sentAt());
        }
    }
}
