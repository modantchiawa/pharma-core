package com.pharmacore.followup.application;

import com.pharmacore.customers.persistence.CustomerEntity;
import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.followup.domain.MessageChannel;
import com.pharmacore.followup.domain.MessageKind;
import com.pharmacore.followup.domain.MessageTemplates;
import com.pharmacore.followup.domain.PhoneNumbers;
import com.pharmacore.printing.domain.ReceiptLayout;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.application.BrandingService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queue of messages to patients. A message exists once per cause ({@code dedupeKey}); it is
 * created only for a customer who gave consent and has a usable phone number. WhatsApp messages
 * wait for a team member to open the pre-filled link and confirm; SMS messages are taken by the
 * dispatcher.
 */
@Service
public class CustomerMessageService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record MessageView(UUID id, UUID customerId, String customerName, MessageKind kind, MessageChannel channel,
                              String phone, String body, String status, String whatsappLink, Instant createdAt,
                              Instant sentAt, String sentBy, int attempts, String lastError) {
    }

    public record Queue(long pending, long sentToday, List<MessageView> messages) {
    }

    /** What a rule wants to say; {@code values} fills the template, {@code produit} and the like. */
    public record Draft(UUID customerId, MessageKind kind, String dedupeKey, String sourceType, UUID sourceId,
                        Map<String, String> values) {
    }

    private final CustomerRepository customers;
    private final FollowUpSettingsService settings;
    private final BrandingService branding;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CustomerMessageService(CustomerRepository customers, FollowUpSettingsService settings,
                                  BrandingService branding, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.customers = customers;
        this.settings = settings;
        this.branding = branding;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Queues the message when the customer agreed, has a phone number and the cause is new.
     *
     * @return the message identifier, empty when nothing was queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> queue(Draft draft) {
        CustomerEntity customer = customers.findById(draft.customerId()).orElse(null);
        if (customer == null || !customer.active() || !customer.messagingConsent()) {
            return Optional.empty();
        }
        var identity = branding.current().branding();
        Optional<String> phone = PhoneNumbers.international(customer.phone(), identity.countryCode());
        if (phone.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> values = new HashMap<>(draft.values());
        values.putIfAbsent("client", customer.displayName());
        values.putIfAbsent("pharmacie", identity.displayName());
        values.putIfAbsent("telephone", identity.phone() == null ? "" : identity.phone());
        String body = MessageTemplates.render(settings.effective().values().template(draft.kind()), values);
        if (body.isBlank()) {
            return Optional.empty();
        }
        MessageChannel channel = customer.preferredChannel();
        if (channel == MessageChannel.SMS && !settings.effective().values().smsConfigured()) {
            channel = MessageChannel.WHATSAPP;
        }
        return insert(customer.id(), draft, phone.get(), body, channel);
    }

    /** A message typed by the team for one customer, sent on their preferred channel. */
    @Transactional
    public MessageView compose(UUID customerId, String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessRuleException("empty-message", "Le message est vide");
        }
        CustomerEntity customer = customers.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (!customer.messagingConsent()) {
            throw new BusinessRuleException("no-consent", "Ce client n'a pas accepté de recevoir des messages");
        }
        var identity = branding.current().branding();
        String phone = PhoneNumbers.international(customer.phone(), identity.countryCode())
                .orElseThrow(() -> new BusinessRuleException("no-phone", "Ce client n'a pas de numéro utilisable"));
        MessageChannel channel = customer.preferredChannel() == MessageChannel.SMS
                && settings.effective().values().smsConfigured() ? MessageChannel.SMS : MessageChannel.WHATSAPP;
        UUID id = insert(customerId, new Draft(customerId, MessageKind.CUSTOM, "CUSTOM:" + UUID.randomUUID(),
                null, null, Map.of()), phone, MessageTemplates.render(text.trim(), Map.of()), channel).orElseThrow();
        return get(id);
    }

    private Optional<UUID> insert(UUID customerId, Draft draft, String phone, String body, MessageChannel channel) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        UUID by = TenantContext.actor() instanceof Actor.TenantUser user ? user.userId() : null;
        int inserted = jdbc.sql("""
                        INSERT INTO customer_message (id, tenant_id, customer_id, kind, channel, phone, body, status,
                                                      dedupe_key, source_type, source_id, created_at, created_by,
                                                      next_attempt_at)
                        VALUES (:id, :tenant, :customer, :kind, :channel, :phone, :body, 'PENDING', :key, :sourceType,
                                :sourceId, :at, :by, :at)
                        ON CONFLICT (tenant_id, dedupe_key) DO NOTHING""")
                .param("id", id)
                .param("tenant", TenantContext.requireTenant().value())
                .param("customer", customerId)
                .param("kind", draft.kind().name())
                .param("channel", channel.name())
                .param("phone", phone)
                .param("body", body)
                .param("key", draft.dedupeKey())
                .param("sourceType", draft.sourceType())
                .param("sourceId", draft.sourceId())
                .param("at", Timestamp.from(now))
                .param("by", by)
                .update();
        if (inserted == 0) {
            return Optional.empty();
        }
        audit.record("CUSTOMER_MESSAGE_QUEUED", "CUSTOMER_MESSAGE", id, Map.of(
                "kind", draft.kind().name(), "channel", channel.name()));
        return Optional.of(id);
    }

    @Transactional(readOnly = true)
    public Queue queue(String status, int limit) {
        UUID tenant = TenantContext.requireTenant().value();
        List<MessageView> messages = jdbc.sql("""
                        SELECT m.id, m.customer_id, c.display_name, m.kind, m.channel, m.phone, m.body, m.status,
                               m.created_at, m.sent_at, u.display_name AS sent_by, m.attempts, m.last_error
                        FROM customer_message m
                        JOIN customer c ON c.id = m.customer_id
                        LEFT JOIN app_user u ON u.id = m.sent_by
                        WHERE m.tenant_id = :tenant AND (:status::text IS NULL OR m.status = :status::text)
                        ORDER BY m.created_at DESC
                        LIMIT :limit""")
                .param("tenant", tenant)
                .param("status", status)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, rowNum) -> view(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getString("display_name"), rs.getString("kind"), rs.getString("channel"),
                        rs.getString("phone"), rs.getString("body"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                        rs.getString("sent_by"), rs.getInt("attempts"), rs.getString("last_error")))
                .list();
        LocalDate today = LocalDate.now(clock);
        Map<String, Long> counts = new HashMap<>();
        jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                               count(*) FILTER (WHERE status = 'SENT' AND sent_at >= :since) AS sent_today
                        FROM customer_message WHERE tenant_id = :tenant""")
                .param("tenant", tenant)
                .param("since", Timestamp.from(today.atStartOfDay(clock.getZone()).toInstant()))
                .query(rs -> {
                    counts.put("pending", rs.getLong("pending"));
                    counts.put("sentToday", rs.getLong("sent_today"));
                });
        return new Queue(counts.getOrDefault("pending", 0L), counts.getOrDefault("sentToday", 0L), messages);
    }

    @Transactional(readOnly = true)
    public MessageView get(UUID id) {
        return jdbc.sql("""
                        SELECT m.id, m.customer_id, c.display_name, m.kind, m.channel, m.phone, m.body, m.status,
                               m.created_at, m.sent_at, u.display_name AS sent_by, m.attempts, m.last_error
                        FROM customer_message m
                        JOIN customer c ON c.id = m.customer_id
                        LEFT JOIN app_user u ON u.id = m.sent_by
                        WHERE m.id = :id""")
                .param("id", id)
                .query((rs, rowNum) -> view(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getString("display_name"), rs.getString("kind"), rs.getString("channel"),
                        rs.getString("phone"), rs.getString("body"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                        rs.getString("sent_by"), rs.getInt("attempts"), rs.getString("last_error")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Message", id));
    }

    /** The team opened the WhatsApp link and sent the message: recorded as sent by them. */
    @Transactional
    public MessageView markSent(UUID id) {
        var user = TenantContext.requireTenantUser();
        int updated = jdbc.sql("""
                        UPDATE customer_message SET status = 'SENT', sent_at = :at, sent_by = :by, version = version + 1
                        WHERE id = :id AND status IN ('PENDING', 'FAILED')""")
                .param("id", id).param("at", Timestamp.from(clock.instant())).param("by", user.userId())
                .update();
        if (updated == 0) {
            throw new BusinessRuleException("message-not-pending", "Ce message n'est plus en attente");
        }
        audit.record("CUSTOMER_MESSAGE_SENT", "CUSTOMER_MESSAGE", id, Map.of("by", "staff"));
        return get(id);
    }

    @Transactional
    public MessageView cancel(UUID id) {
        int updated = jdbc.sql("""
                        UPDATE customer_message SET status = 'CANCELLED', version = version + 1
                        WHERE id = :id AND status IN ('PENDING', 'FAILED')""")
                .param("id", id).update();
        if (updated == 0) {
            throw new BusinessRuleException("message-not-pending", "Ce message n'est plus en attente");
        }
        audit.record("CUSTOMER_MESSAGE_CANCELLED", "CUSTOMER_MESSAGE", id, Map.of());
        return get(id);
    }

    /** Amount formatted for a message, in the pharmacy's currency. */
    public String money(long cents) {
        return ReceiptLayout.money(cents, branding.current().currency());
    }

    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    private static MessageView view(UUID id, UUID customerId, String customerName, String kind, String channel,
                                    String phone, String body, String status, Instant createdAt, Instant sentAt,
                                    String sentBy, int attempts, String lastError) {
        MessageChannel ch = MessageChannel.valueOf(channel);
        return new MessageView(id, customerId, customerName, MessageKind.valueOf(kind), ch, phone, body, status,
                ch == MessageChannel.WHATSAPP ? PhoneNumbers.whatsappLink(phone, body) : null, createdAt, sentAt,
                sentBy, attempts, lastError);
    }
}
