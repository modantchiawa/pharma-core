package com.pharmacore.control.application;

import com.pharmacore.control.domain.Amounts;
import com.pharmacore.control.persistence.ControlAlertEntity;
import com.pharmacore.control.persistence.ControlAlertEntity.Draft;
import com.pharmacore.control.persistence.ControlAlertEntity.Kind;
import com.pharmacore.control.persistence.ControlAlertEntity.Severity;
import com.pharmacore.control.persistence.ControlAlertRepository;
import com.pharmacore.control.persistence.WebhookOutboxEntity;
import com.pharmacore.control.persistence.WebhookOutboxRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The owner's alert feed. An alert is written in the transaction of the event that raised it,
 * pushed to the screens after commit and, when the owner configured a webhook, queued for it in
 * the same transaction. Alerts carry staff names, amounts and products, never a customer.
 */
@Service
public class AlertService {

    public record AlertView(UUID id, Kind kind, Severity severity, String title, String detail, String resourceType,
                            UUID resourceId, UUID staffUserId, String staffName, Long amountCents,
                            Instant occurredAt, Instant createdAt, Instant acknowledgedAt, String acknowledgedBy) {
    }

    public record Feed(long unread, List<AlertView> alerts) {
    }

    /** Recent webhook deliveries, for the settings screen. */
    public record DeliveryView(UUID id, UUID alertId, String status, int attempts, Instant nextAttemptAt,
                               String lastError, Instant createdAt, Instant deliveredAt) {
    }

    private final ControlAlertRepository alerts;
    private final WebhookOutboxRepository outbox;
    private final ControlSettingsService settings;
    private final TenantRepository tenants;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public AlertService(ControlAlertRepository alerts, WebhookOutboxRepository outbox, ControlSettingsService settings,
                        TenantRepository tenants, AuditTrail audit, ApplicationEventPublisher events, JdbcClient jdbc,
                        ObjectMapper json, Clock clock) {
        this.alerts = alerts;
        this.outbox = outbox;
        this.settings = settings;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AlertView raise(Draft draft) {
        Instant now = clock.instant();
        ControlAlertEntity alert = alerts.save(new ControlAlertEntity(draft, now));
        String staffName = draft.staffUserId() == null ? null : displayName(draft.staffUserId());
        AlertView view = view(alert, staffName, null);
        if (settings.effective().webhookActive()) {
            outbox.save(new WebhookOutboxEntity(alert.id(), payload(view), now));
        }
        events.publishEvent(new ControlAlertRaised(TenantContext.requireTenant().value(), view));
        return view;
    }

    @Transactional(readOnly = true)
    public Feed feed(boolean unreadOnly, int limit) {
        List<ControlAlertEntity> page = alerts.findFeed(unreadOnly, PageRequest.of(0, Math.clamp(limit, 1, 200)));
        Map<UUID, String> names = names(page.stream()
                .flatMap(a -> Stream.of(a.staffUserId(), a.acknowledgedBy()))
                .filter(Objects::nonNull).distinct().toList());
        return new Feed(alerts.countUnread(), page.stream()
                .map(a -> view(a, names.get(a.staffUserId()), names.get(a.acknowledgedBy())))
                .toList());
    }

    @Transactional(readOnly = true)
    public long unread() {
        return alerts.countUnread();
    }

    @Transactional
    public AlertView acknowledge(UUID alertId) {
        var user = TenantContext.requireTenantUser();
        ControlAlertEntity alert = alerts.findById(alertId).orElseThrow(() -> new NotFoundException("Alert", alertId));
        if (alert.acknowledge(user.userId(), clock.instant())) {
            audit.record("CONTROL_ALERT_ACKNOWLEDGED", "CONTROL_ALERT", alertId, Map.of(
                    "kind", alert.kind().name(), "title", alert.title()));
        }
        Map<UUID, String> names = names(Stream.of(alert.staffUserId(), alert.acknowledgedBy())
                .filter(Objects::nonNull).toList());
        return view(alert, names.get(alert.staffUserId()), names.get(alert.acknowledgedBy()));
    }

    /** Marks every unread alert as seen; returns how many. */
    @Transactional
    public int acknowledgeAll() {
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        List<ControlAlertEntity> unread = alerts.findUnread();
        unread.forEach(alert -> alert.acknowledge(user.userId(), now));
        if (!unread.isEmpty()) {
            audit.record("CONTROL_ALERTS_ACKNOWLEDGED", "CONTROL_ALERT", user.tenant().value(), Map.of(
                    "count", Integer.toString(unread.size())));
        }
        return unread.size();
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> deliveries(int limit) {
        return outbox.findRecent(PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
                .map(o -> new DeliveryView(o.id(), o.alertId(), o.status().name(), o.attempts(), o.nextAttemptAt(),
                        o.lastError(), o.createdAt(), o.deliveredAt()))
                .toList();
    }

    /** Raises a test alert so the owner can check the bell and the webhook end to end. */
    @Transactional
    public AlertView test() {
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        return raise(new Draft(Kind.TEST, Severity.INFO, "Alerte de test",
                "Alerte de test envoyée par " + user.displayName() + " depuis les réglages du contrôle interne.",
                null, null, user.userId(), null, now));
    }

    /**
     * JSON posted to the webhook: no customer or patient data, only what the owner needs to act
     * (and what an SMS or WhatsApp relay can forward as is, the {@code text} field).
     */
    String payload(AlertView alert) {
        UUID tenant = TenantContext.requireTenant().value();
        var pharmacy = tenants.findById(tenant).orElseThrow();
        String name = jdbc.sql("SELECT display_name FROM pharmacy_config WHERE tenant_id = :tenant")
                .param("tenant", tenant).query(String.class).optional().orElse(pharmacy.legalName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "control.alert");
        body.put("id", alert.id().toString());
        body.put("pharmacy", Map.of("slug", pharmacy.slug(), "name", name));
        body.put("kind", alert.kind().name());
        body.put("severity", alert.severity().name());
        body.put("title", alert.title());
        body.put("detail", alert.detail());
        if (alert.amountCents() != null) {
            body.put("amount", Map.of("minorUnits", alert.amountCents(), "currency", pharmacy.currency(),
                    "text", Amounts.format(alert.amountCents(), pharmacy.currency())));
        }
        body.put("staff", alert.staffName());
        body.put("occurredAt", alert.occurredAt().toString());
        body.put("text", name + " · " + alert.title() + " : " + alert.detail());
        try {
            return json.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the webhook payload", e);
        }
    }

    private static AlertView view(ControlAlertEntity a, String staffName, String acknowledgedBy) {
        return new AlertView(a.id(), a.kind(), a.severity(), a.title(), a.detail(), a.resourceType(), a.resourceId(),
                a.staffUserId(), staffName, a.amountCents(), a.occurredAt(), a.createdAt(), a.acknowledgedAt(),
                acknowledgedBy);
    }

    String displayName(UUID userId) {
        return names(List.of(userId)).get(userId);
    }

    private Map<UUID, String> names(List<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, display_name FROM app_user WHERE id = ANY(:ids)")
                .param("ids", userIds.toArray(UUID[]::new))
                .query((RowCallbackHandler) rs -> names.put(
                        rs.getObject("id", UUID.class), rs.getString("display_name")));
        return names;
    }
}
