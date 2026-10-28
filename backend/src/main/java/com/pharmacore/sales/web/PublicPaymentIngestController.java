package com.pharmacore.sales.web;

import com.pharmacore.network.application.PublicRateLimiter;
import com.pharmacore.network.application.RateLimitExceededException;
import com.pharmacore.sales.application.PaymentChannelSettingsService;
import com.pharmacore.sales.application.PaymentNotificationService;
import com.pharmacore.sales.application.PaymentNotificationService.Incoming;
import com.pharmacore.sales.application.PaymentNotificationService.Source;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.tenant.persistence.TenantRepository;
import com.pharmacore.tenant.persistence.TenantTopology;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Where the confirmations come in without a user session: the pharmacy's phone forwards each
 * operator SMS (any "SMS forwarder" application, pointed at the hub's address on the local
 * network), and operators post their webhooks (to the cloud, usually). Both present the
 * pharmacy's ingest token. On a hub the pharmacy is the hub's; on the cloud it is named by its
 * slug, and a hub pharmacy's events are only stored for the hub to pick up at its next sync.
 */
@RestController
class PublicPaymentIngestController {

    static final String TOKEN_HEADER = "X-PharmaCore-Token";
    private static final List<String> TEXT_FIELDS = List.of("text", "message", "body", "content", "sms", "msg");
    private static final List<String> SENDER_FIELDS = List.of("from", "sender", "address", "number", "msisdn");
    private static final List<String> TIME_FIELDS = List.of("receivedAt", "received_at", "timestamp", "sentStamp", "date");

    private final PaymentNotificationService notifications;
    private final PaymentChannelSettingsService settings;
    private final PublicRateLimiter rateLimiter;
    private final NodeIdentity node;
    private final TenantRepository tenants;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Clock clock;

    PublicPaymentIngestController(PaymentNotificationService notifications, PaymentChannelSettingsService settings,
                                  PublicRateLimiter rateLimiter, NodeIdentity node, TenantRepository tenants,
                                  JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.notifications = notifications;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.node = node;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    record Outcome(String status, UUID notificationId, String matched) {
    }

    @PostMapping(value = "/api/public/payments/{slug}/sms", consumes = MediaType.APPLICATION_JSON_VALUE)
    Outcome smsJson(@PathVariable String slug, @RequestBody Map<String, Object> body,
                    @RequestHeader(value = TOKEN_HEADER, required = false) String headerToken,
                    @RequestParam(value = "token", required = false) String queryToken, HttpServletRequest http) {
        return receive(slug, Source.SMS, stringify(body), headerToken, queryToken, http);
    }

    @PostMapping(value = "/api/public/payments/{slug}/sms", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Outcome smsForm(@PathVariable String slug, @RequestParam Map<String, String> form,
                    @RequestHeader(value = TOKEN_HEADER, required = false) String headerToken, HttpServletRequest http) {
        Map<String, String> fields = new LinkedHashMap<>(form);
        String token = fields.remove("token");
        return receive(slug, Source.SMS, fields, headerToken, token, http);
    }

    @PostMapping(value = "/api/public/payments/{slug}/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    Outcome webhook(@PathVariable String slug, @RequestBody Map<String, Object> body,
                    @RequestHeader(value = TOKEN_HEADER, required = false) String headerToken,
                    @RequestParam(value = "token", required = false) String queryToken, HttpServletRequest http) {
        return receive(slug, Source.WEBHOOK, stringify(body), headerToken, queryToken, http);
    }

    private Outcome receive(String slug, Source source, Map<String, String> fields, String headerToken,
                            String queryToken, HttpServletRequest http) {
        if (!rateLimiter.tryAcquire(clientAddress(http))) {
            throw new RateLimitExceededException("Trop de confirmations reçues de cette adresse");
        }
        String token = headerToken != null && !headerToken.isBlank() ? headerToken : queryToken;
        TenantId tenant = resolve(slug).orElseThrow(() -> new AccessDeniedException("Unknown pharmacy"));
        var context = RequestContext.of(Actor.SystemProcess.forTenant("payment-ingest", tenant));
        return TenantContext.call(context, () -> {
            if (!tx.execute(status -> settings.verify(token))) {
                throw new AccessDeniedException("Invalid ingest token");
            }
            String text = first(fields, TEXT_FIELDS);
            String sender = first(fields, SENDER_FIELDS);
            Instant receivedAt = parseInstant(first(fields, TIME_FIELDS));
            String provider = fields.get("provider");
            Long amount = parseAmount(fields.get("amount"), fields.get("amountCents"));
            if (node.hubTenant().isEmpty() && isHubPharmacy(tenant)) {
                // Cloud receiving for a hub pharmacy: kept for the hub, which reads it at its next sync
                UUID id = store(tenant, source, provider, fields);
                return new Outcome("STORED_FOR_HUB", id, null);
            }
            String key = source == Source.WEBHOOK && fields.get("id") != null ? "WEBHOOK:" + fields.get("id") : null;
            return notifications.ingest(new Incoming(source, sender, text, provider, amount, fields.get("reference"),
                            fields.get("phone"), receivedAt, key, null))
                    .map(view -> new Outcome("RECEIVED", view.id(), view.status()))
                    .orElse(new Outcome("DUPLICATE", null, null));
        });
    }

    private Optional<TenantId> resolve(String slug) {
        if (node.hubTenant().isPresent()) {
            return node.slug().filter(slug::equals).flatMap(s -> node.hubTenant());
        }
        var discovery = RequestContext.of(Actor.SystemProcess.crossTenant("payment-ingest"));
        return TenantContext.call(discovery, () -> tx.execute(status -> jdbc.sql("""
                        SELECT id FROM tenant WHERE slug = :slug AND status = 'ACTIVE'""")
                .param("slug", slug).query(UUID.class).optional().map(TenantId::new)));
    }

    private boolean isHubPharmacy(TenantId tenant) {
        return tx.execute(status -> tenants.findById(tenant.value())
                .map(t -> t.topology() == TenantTopology.HUB).orElse(false));
    }

    private UUID store(TenantId tenant, Source source, String provider, Map<String, String> fields) {
        UUID id = UUID.randomUUID();
        Map<String, String> payload = new LinkedHashMap<>(fields);
        payload.put("source", source.name());
        tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO payment_webhook_event (id, tenant_id, provider, payload, received_at)
                        VALUES (:id, :tenant, :provider, :payload, :at)""")
                .param("id", id).param("tenant", tenant.value()).param("provider", provider)
                .param("payload", json.writeValueAsString(payload)).param("at", Timestamp.from(clock.instant()))
                .update());
        return id;
    }

    private static Map<String, String> stringify(Map<String, Object> body) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (body != null) {
            body.forEach((key, value) -> {
                if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                    fields.put(key, String.valueOf(value));
                }
            });
        }
        return fields;
    }

    private static String first(Map<String, String> fields, List<String> names) {
        for (String name : names) {
            String value = fields.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.matches("\\d{10,13}")) {
                long number = Long.parseLong(value);
                return Instant.ofEpochMilli(number < 100_000_000_000L ? number * 1000 : number);
            }
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long parseAmount(String amount, String amountCents) {
        try {
            if (amountCents != null && !amountCents.isBlank()) {
                return Long.parseLong(amountCents.trim());
            }
            if (amount != null && !amount.isBlank()) {
                return Math.round(Double.parseDouble(amount.replaceAll("[^0-9.,]", "").replace(',', '.')));
            }
        } catch (NumberFormatException ignored) {
            // the text will be read instead
        }
        return null;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RateLimitExceededException.class)
    org.springframework.http.ProblemDetail rateLimited(RateLimitExceededException e) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                e.getMessage());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AccessDeniedException.class)
    org.springframework.http.ProblemDetail denied(AccessDeniedException e) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.FORBIDDEN,
                "Jeton d'ingestion invalide ou pharmacie inconnue");
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
