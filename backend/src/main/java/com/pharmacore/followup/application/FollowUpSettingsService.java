package com.pharmacore.followup.application;

import com.pharmacore.followup.domain.MessageKind;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Per-pharmacy settings of the patient follow-up and of the owner's evening digest. */
@Service
public class FollowUpSettingsService {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    /** What the pharmacy chose; {@code templates} holds only the kinds it customised. */
    public record Values(boolean enabled, boolean treatmentEndEnabled, boolean renewalEnabled, int renewalDaysAhead,
                         boolean productWishEnabled, boolean creditReminderEnabled, int creditReminderAfterDays,
                         int creditReminderEveryDays, Map<MessageKind, String> templates, String smsGatewayUrl,
                         String smsSender, boolean digestEnabled, int digestHour, String digestPhone) {

        public Values {
            templates = templates == null ? Map.of() : Map.copyOf(templates);
        }

        /** The template in force for a kind: the pharmacy's own, or the default one. */
        public String template(MessageKind kind) {
            String custom = templates.get(kind);
            return custom == null || custom.isBlank() ? kind.defaultTemplate() : custom;
        }

        public boolean smsConfigured() {
            return smsGatewayUrl != null && !smsGatewayUrl.isBlank();
        }
    }

    public record View(Values values, Map<MessageKind, String> defaultTemplates, boolean smsTokenSet,
                       Instant updatedAt, long version) {
    }

    /** Values with the secret, for the gateway only. */
    public record Effective(Values values, String smsGatewayToken) {
    }

    /** A null token keeps the stored one; an empty one clears it. */
    public record Update(Values values, String smsGatewayToken) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final Clock clock;

    public FollowUpSettingsService(JdbcClient jdbc, ObjectMapper json, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    public static Values defaults() {
        return new Values(true, true, true, 3, true, false, 30, 14, Map.of(), null, null, true, 21, null);
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Effective effective() {
        return load().map(row -> new Effective(row.values(), row.smsGatewayToken()))
                .orElseGet(() -> new Effective(defaults(), null));
    }

    @Transactional(readOnly = true)
    public View view() {
        Map<MessageKind, String> defaults = new EnumMap<>(MessageKind.class);
        for (MessageKind kind : MessageKind.values()) {
            defaults.put(kind, kind.defaultTemplate());
        }
        return load().map(row -> new View(row.values(), defaults, row.smsGatewayToken() != null, row.updatedAt(),
                        row.version()))
                .orElseGet(() -> new View(defaults(), defaults, false, null, 0));
    }

    @Transactional
    public View update(Update update) {
        Values v = update.values();
        if (v.smsConfigured() && !v.smsGatewayUrl().startsWith("https://")) {
            throw new BusinessRuleException("sms-gateway-https", "La passerelle SMS doit être appelée en HTTPS");
        }
        Map<String, String> templates = new LinkedHashMap<>();
        v.templates().forEach((kind, template) -> {
            if (template != null && !template.isBlank() && !template.equals(kind.defaultTemplate())) {
                templates.put(kind.name(), template.trim());
            }
        });
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        String token = update.smsGatewayToken();
        jdbc.sql("""
                        INSERT INTO followup_settings (tenant_id, enabled, treatment_end_enabled, renewal_enabled,
                            renewal_days_ahead, product_wish_enabled, credit_reminder_enabled, credit_reminder_after_days,
                            credit_reminder_every_days, templates, sms_gateway_url, sms_gateway_token, sms_sender,
                            digest_enabled, digest_hour, digest_phone, updated_by, updated_at)
                        VALUES (:tenant, :enabled, :treatment, :renewal, :renewalDays, :wish, :credit, :creditAfter,
                                :creditEvery, :templates::jsonb, :smsUrl, :smsToken, :smsSender, :digest, :digestHour,
                                :digestPhone, :by, :at)
                        ON CONFLICT (tenant_id) DO UPDATE
                            SET enabled = EXCLUDED.enabled, treatment_end_enabled = EXCLUDED.treatment_end_enabled,
                                renewal_enabled = EXCLUDED.renewal_enabled, renewal_days_ahead = EXCLUDED.renewal_days_ahead,
                                product_wish_enabled = EXCLUDED.product_wish_enabled,
                                credit_reminder_enabled = EXCLUDED.credit_reminder_enabled,
                                credit_reminder_after_days = EXCLUDED.credit_reminder_after_days,
                                credit_reminder_every_days = EXCLUDED.credit_reminder_every_days,
                                templates = EXCLUDED.templates, sms_gateway_url = EXCLUDED.sms_gateway_url,
                                sms_gateway_token = CASE WHEN :keepToken THEN followup_settings.sms_gateway_token
                                                         ELSE EXCLUDED.sms_gateway_token END,
                                sms_sender = EXCLUDED.sms_sender, digest_enabled = EXCLUDED.digest_enabled,
                                digest_hour = EXCLUDED.digest_hour, digest_phone = EXCLUDED.digest_phone,
                                updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at,
                                version = followup_settings.version + 1""")
                .param("tenant", user.tenant().value())
                .param("enabled", v.enabled())
                .param("treatment", v.treatmentEndEnabled())
                .param("renewal", v.renewalEnabled())
                .param("renewalDays", Math.clamp(v.renewalDaysAhead(), 0, 30))
                .param("wish", v.productWishEnabled())
                .param("credit", v.creditReminderEnabled())
                .param("creditAfter", Math.clamp(v.creditReminderAfterDays(), 1, 365))
                .param("creditEvery", Math.clamp(v.creditReminderEveryDays(), 1, 365))
                .param("templates", json.writeValueAsString(templates))
                .param("smsUrl", blankToNull(v.smsGatewayUrl()))
                .param("smsToken", token == null ? null : blankToNull(token))
                .param("keepToken", token == null)
                .param("smsSender", blankToNull(v.smsSender()))
                .param("digest", v.digestEnabled())
                .param("digestHour", Math.clamp(v.digestHour(), 0, 23))
                .param("digestPhone", blankToNull(v.digestPhone()))
                .param("by", user.userId())
                .param("at", Timestamp.from(now))
                .update();
        audit.record("FOLLOWUP_SETTINGS_UPDATED", "FOLLOWUP_SETTINGS", user.tenant().value(), Map.of(
                "enabled", Boolean.toString(v.enabled()), "sms", Boolean.toString(v.smsConfigured()),
                "digestHour", Integer.toString(v.digestHour())));
        return view();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Row(Values values, String smsGatewayToken, Instant updatedAt, long version) {
    }

    private Optional<Row> load() {
        UUID tenant = TenantContext.requireTenant().value();
        return jdbc.sql("""
                        SELECT enabled, treatment_end_enabled, renewal_enabled, renewal_days_ahead, product_wish_enabled,
                               credit_reminder_enabled, credit_reminder_after_days, credit_reminder_every_days,
                               templates::text AS templates, sms_gateway_url, sms_gateway_token, sms_sender,
                               digest_enabled, digest_hour, digest_phone, updated_at, version
                        FROM followup_settings WHERE tenant_id = :tenant""")
                .param("tenant", tenant)
                .query((rs, rowNum) -> {
                    Map<MessageKind, String> templates = new EnumMap<>(MessageKind.class);
                    json.readValue(rs.getString("templates"), STRING_MAP).forEach((kind, template) -> {
                        try {
                            templates.put(MessageKind.valueOf(kind), template);
                        } catch (IllegalArgumentException ignored) {
                            // a kind of a later version: ignored by this one
                        }
                    });
                    return new Row(new Values(rs.getBoolean("enabled"), rs.getBoolean("treatment_end_enabled"),
                            rs.getBoolean("renewal_enabled"), rs.getInt("renewal_days_ahead"),
                            rs.getBoolean("product_wish_enabled"), rs.getBoolean("credit_reminder_enabled"),
                            rs.getInt("credit_reminder_after_days"), rs.getInt("credit_reminder_every_days"), templates,
                            rs.getString("sms_gateway_url"), rs.getString("sms_sender"), rs.getBoolean("digest_enabled"),
                            rs.getInt("digest_hour"), rs.getString("digest_phone")),
                            rs.getString("sms_gateway_token"), rs.getTimestamp("updated_at").toInstant(),
                            rs.getLong("version"));
                })
                .optional();
    }
}
