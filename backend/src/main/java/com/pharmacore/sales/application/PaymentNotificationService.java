package com.pharmacore.sales.application;

import com.pharmacore.sales.domain.PaymentMatching;
import com.pharmacore.sales.domain.PaymentNotice;
import com.pharmacore.sales.domain.PaymentSmsParser;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment confirmations received live: an operator SMS forwarded by the pharmacy's phone, an
 * operator webhook, or a message typed by the team on any terminal. Each is read, kept once
 * (dedupe key), matched against the pending mobile money payments when the match is certain,
 * and pushed to every terminal of the pharmacy over the local broker: no Internet involved.
 */
@Service
public class PaymentNotificationService {

    public enum Source {
        SMS,
        WEBHOOK,
        MANUAL
    }

    public record NotificationView(UUID id, Source source, String provider, Long amountCents, String reference,
                                   String payerPhone, String rawText, boolean incoming, String status, UUID paymentId,
                                   String ticketNumber, Instant receivedAt, Instant matchedAt, String matchedBy) {
    }

    public record Feed(long open, List<NotificationView> notifications) {
    }

    /** What arrived, before it is read. */
    public record Incoming(Source source, String sender, String text, String provider, Long amountCents,
                           String reference, String payerPhone, Instant receivedAt, String dedupeKey,
                           String terminalId) {
    }

    private final PaymentReconciliationService reconciliation;
    private final PaymentChannelSettingsService settings;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final Clock clock;

    public PaymentNotificationService(PaymentReconciliationService reconciliation,
                                      PaymentChannelSettingsService settings, AuditTrail audit,
                                      ApplicationEventPublisher events, JdbcClient jdbc, Clock clock) {
        this.reconciliation = reconciliation;
        this.settings = settings;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records a confirmation and matches it when the pharmacy allows automatic confirmation.
     *
     * @return the stored notification, empty when the same confirmation was already recorded
     */
    @Transactional
    public Optional<NotificationView> ingest(Incoming incoming) {
        var values = settings.effective();
        PaymentNotice notice;
        if (incoming.source() == Source.MANUAL) {
            notice = new PaymentNotice(incoming.provider(), incoming.amountCents(),
                    blankToNull(incoming.reference()), blankToNull(incoming.payerPhone()), true);
        } else {
            notice = PaymentSmsParser.parse(incoming.sender(), incoming.text(), values.patterns());
            if (incoming.provider() != null && notice.provider() == null) {
                notice = new PaymentNotice(incoming.provider(), notice.amountCents(), notice.reference(),
                        notice.payerPhone(), notice.incoming());
            }
            if (incoming.amountCents() != null && notice.amountCents() == null) {
                notice = new PaymentNotice(notice.provider(), incoming.amountCents(),
                        blankToNull(incoming.reference()), notice.payerPhone(), true);
            }
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant receivedAt = incoming.receivedAt() == null ? now : incoming.receivedAt();
        UUID by = TenantContext.actor() instanceof Actor.TenantUser user ? user.userId() : null;
        String key = incoming.dedupeKey() != null ? incoming.dedupeKey()
                : incoming.source().name() + ":" + fingerprint(incoming.sender(), incoming.text(), receivedAt);
        int inserted = jdbc.sql("""
                        INSERT INTO payment_notification (id, tenant_id, source, provider, amount_cents, reference, payer_phone,
                                                          raw_text, incoming, status, dedupe_key, recorded_by, terminal_id,
                                                          received_at, created_at)
                        VALUES (:id, :tenant, :source, :provider, :amount, :reference, :phone, :raw, :incoming,
                                :status, :key, :by, :terminal, :received, :now)
                        ON CONFLICT (tenant_id, dedupe_key) DO NOTHING""")
                .param("id", id)
                .param("tenant", TenantContext.requireTenant().value())
                .param("source", incoming.source().name())
                .param("provider", notice.provider())
                .param("amount", notice.amountCents())
                .param("reference", notice.reference())
                .param("phone", notice.payerPhone())
                .param("raw", truncate(incoming.text(), 1000))
                .param("incoming", notice.usable())
                .param("status", notice.usable() ? "UNMATCHED" : "IGNORED")
                .param("key", truncate(key, 120))
                .param("by", by)
                .param("terminal", incoming.terminalId())
                .param("received", Timestamp.from(receivedAt))
                .param("now", Timestamp.from(now))
                .update();
        if (inserted == 0) {
            return Optional.empty();
        }
        audit.record("PAYMENT_NOTIFICATION_RECEIVED", "PAYMENT_NOTIFICATION", id, Map.of(
                "source", incoming.source().name(), "provider", String.valueOf(notice.provider()),
                "amountCents", String.valueOf(notice.amountCents()), "usable", Boolean.toString(notice.usable())));
        if (notice.usable() && values.autoConfirm()) {
            Instant since = now.minus(Duration.ofHours(values.matchWindowHours()));
            PaymentMatching.choose(notice, reconciliation.pendingCandidates(since))
                    .ifPresent(paymentId -> link(id, paymentId, by, now));
        }
        NotificationView view = get(id);
        events.publishEvent(new PaymentNotificationReceived(TenantContext.requireTenant().value(), view));
        return Optional.of(view);
    }

    /**
     * A sale just recorded (live or replayed from an offline terminal): its pending mobile money
     * payments take the confirmations that already arrived for them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void matchSale(SaleEntity sale) {
        var values = settings.effective();
        if (!values.autoConfirm()) {
            return;
        }
        for (SalePaymentEntity payment : sale.payments()) {
            if (payment.confirmationStatus() != SalePaymentEntity.ConfirmationStatus.PENDING
                    || payment.provider() == null) {
                continue;
            }
            List<NotificationView> open = jdbc.sql("""
                            SELECT id, provider, amount_cents, reference, payer_phone
                            FROM payment_notification
                            WHERE tenant_id = :tenant AND status = 'UNMATCHED' AND incoming
                              AND amount_cents = :amount AND (provider IS NULL OR provider = :provider)
                              AND received_at >= :since
                            ORDER BY received_at DESC""")
                    .param("tenant", TenantContext.requireTenant().value())
                    .param("amount", payment.amountCents())
                    .param("provider", payment.provider())
                    .param("since", Timestamp.from(clock.instant().minus(Duration.ofHours(values.matchWindowHours()))))
                    .query((rs, rowNum) -> new NotificationView(rs.getObject("id", UUID.class), null,
                            rs.getString("provider"), rs.getLong("amount_cents"), rs.getString("reference"),
                            rs.getString("payer_phone"), null, true, "UNMATCHED", null, null, null, null, null))
                    .list();
            Optional<NotificationView> chosen;
            if (payment.reference() != null && !payment.reference().isBlank()) {
                chosen = open.stream().filter(n -> payment.reference().trim().equalsIgnoreCase(n.reference())).findFirst();
            } else {
                chosen = open.size() == 1 ? Optional.of(open.getFirst()) : Optional.empty();
            }
            if (chosen.isPresent()) {
                UUID by = TenantContext.actor() instanceof Actor.TenantUser user ? user.userId() : null;
                link(chosen.get().id(), payment.id(), by, clock.instant());
            }
        }
    }

    private void link(UUID notificationId, UUID paymentId, UUID by, Instant now) {
        reconciliation.confirmFromNotification(paymentId, notificationId);
        jdbc.sql("""
                        UPDATE payment_notification SET status = 'MATCHED', payment_id = :payment, matched_at = :at,
                                                        matched_by = :by, version = version + 1
                        WHERE id = :id AND status = 'UNMATCHED'""")
                .param("payment", paymentId).param("at", Timestamp.from(now)).param("by", by)
                .param("id", notificationId)
                .update();
    }

    /** The team links a confirmation to a pending payment by hand (two candidates, typo in the reference…). */
    @Transactional
    public NotificationView claim(UUID notificationId, UUID paymentId) {
        NotificationView current = get(notificationId);
        if (!"UNMATCHED".equals(current.status())) {
            throw new BusinessRuleException("notification-not-open", "Cette confirmation est déjà traitée");
        }
        var user = TenantContext.requireTenantUser();
        link(notificationId, paymentId, user.userId(), clock.instant());
        NotificationView view = get(notificationId);
        events.publishEvent(new PaymentNotificationReceived(user.tenant().value(), view));
        return view;
    }

    @Transactional
    public NotificationView ignore(UUID notificationId) {
        int updated = jdbc.sql("""
                        UPDATE payment_notification SET status = 'IGNORED', version = version + 1
                        WHERE id = :id AND status = 'UNMATCHED'""")
                .param("id", notificationId).update();
        if (updated == 0) {
            throw new BusinessRuleException("notification-not-open", "Cette confirmation est déjà traitée");
        }
        audit.record("PAYMENT_NOTIFICATION_IGNORED", "PAYMENT_NOTIFICATION", notificationId, Map.of());
        return get(notificationId);
    }

    @Transactional(readOnly = true)
    public Feed feed(String status, int limit) {
        UUID tenant = TenantContext.requireTenant().value();
        List<NotificationView> rows = jdbc.sql("""
                        SELECT n.id, n.source, n.provider, n.amount_cents, n.reference, n.payer_phone, n.raw_text, n.incoming,
                               n.status, n.payment_id, s.ticket_number, n.received_at, n.matched_at, u.display_name AS matched_by
                        FROM payment_notification n
                        LEFT JOIN sale_payment p ON p.id = n.payment_id
                        LEFT JOIN sale s ON s.id = p.sale_id
                        LEFT JOIN app_user u ON u.id = n.matched_by
                        WHERE n.tenant_id = :tenant AND (:status::text IS NULL OR n.status = :status::text)
                        ORDER BY n.received_at DESC
                        LIMIT :limit""")
                .param("tenant", tenant)
                .param("status", status)
                .param("limit", Math.clamp(limit, 1, 500))
                .query(this::map)
                .list();
        long open = jdbc.sql("""
                        SELECT count(*) FROM payment_notification WHERE tenant_id = :tenant AND status = 'UNMATCHED'""")
                .param("tenant", tenant).query(Long.class).single();
        return new Feed(open, rows);
    }

    /** The notification a replayed request already created (idempotent manual entry). */
    @Transactional(readOnly = true)
    public NotificationView byDedupeKey(String dedupeKey) {
        UUID id = jdbc.sql("SELECT id FROM payment_notification WHERE tenant_id = :tenant AND dedupe_key = :key")
                .param("tenant", TenantContext.requireTenant().value()).param("key", dedupeKey)
                .query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("PaymentNotification", dedupeKey));
        return get(id);
    }

    @Transactional(readOnly = true)
    public NotificationView get(UUID id) {
        return jdbc.sql("""
                        SELECT n.id, n.source, n.provider, n.amount_cents, n.reference, n.payer_phone, n.raw_text, n.incoming,
                               n.status, n.payment_id, s.ticket_number, n.received_at, n.matched_at, u.display_name AS matched_by
                        FROM payment_notification n
                        LEFT JOIN sale_payment p ON p.id = n.payment_id
                        LEFT JOIN sale s ON s.id = p.sale_id
                        LEFT JOIN app_user u ON u.id = n.matched_by
                        WHERE n.id = :id""")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new NotFoundException("PaymentNotification", id));
    }

    /**
     * Webhooks received by the cloud for this pharmacy and replicated to this node, not yet
     * turned into notifications (the hub runs this; the cloud ingests directly for its own
     * pharmacies).
     *
     * @return how many were ingested
     */
    @Transactional
    public int ingestReplicatedWebhooks() {
        UUID tenant = TenantContext.requireTenant().value();
        record Event(UUID id, String provider, String payload, Instant receivedAt) {
        }
        List<Event> pending = jdbc.sql("""
                        SELECT e.id, e.provider, e.payload, e.received_at
                        FROM payment_webhook_event e
                        WHERE e.tenant_id = :tenant AND e.received_at >= :since
                          AND NOT EXISTS (SELECT 1 FROM payment_notification n
                                          WHERE n.tenant_id = e.tenant_id AND n.dedupe_key = 'WEBHOOK:' || e.id)
                        ORDER BY e.received_at
                        LIMIT 100""")
                .param("tenant", tenant)
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(7))))
                .query((rs, rowNum) -> new Event(rs.getObject("id", UUID.class), rs.getString("provider"),
                        rs.getString("payload"), rs.getTimestamp("received_at").toInstant()))
                .list();
        int done = 0;
        for (Event event : pending) {
            if (ingest(new Incoming(Source.WEBHOOK, event.provider(), event.payload(), event.provider(), null, null,
                    null, event.receivedAt(), "WEBHOOK:" + event.id(), null)).isPresent()) {
                done++;
            }
        }
        return done;
    }

    private NotificationView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new NotificationView(rs.getObject("id", UUID.class), Source.valueOf(rs.getString("source")),
                rs.getString("provider"), rs.getObject("amount_cents") == null ? null : rs.getLong("amount_cents"),
                rs.getString("reference"), rs.getString("payer_phone"), rs.getString("raw_text"),
                rs.getBoolean("incoming"), rs.getString("status"), rs.getObject("payment_id", UUID.class),
                rs.getString("ticket_number"), rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("matched_at") == null ? null : rs.getTimestamp("matched_at").toInstant(),
                rs.getString("matched_by"));
    }

    /** Same SMS forwarded twice (phone retry) within the same minute: one notification. */
    static String fingerprint(String sender, String text, Instant receivedAt) {
        String material = (sender == null ? "" : sender.trim()) + "|" + (text == null ? "" : text.trim()) + "|"
                + receivedAt.getEpochSecond() / 60;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
