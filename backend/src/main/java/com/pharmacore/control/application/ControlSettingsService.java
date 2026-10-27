package com.pharmacore.control.application;

import com.pharmacore.control.domain.ActivityHours;
import com.pharmacore.control.domain.FourEyesPolicy;
import com.pharmacore.control.persistence.ControlSettingsEntity;
import com.pharmacore.control.persistence.ControlSettingsEntity.Separation;
import com.pharmacore.control.persistence.ControlSettingsEntity.Values;
import com.pharmacore.control.persistence.ControlSettingsRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Thresholds and switches of the internal control, edited by the owner only. A pharmacy that
 * never saved them runs on the defaults below; the row is created on first need (the daily draw
 * needs its secret salt, generated once and never shown).
 */
@Service
public class ControlSettingsService {

    private static final TypeReference<List<ActivityDay>> DAYS = new TypeReference<>() {
    };
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One weekday of the activity hours, times as {@code HH:mm}. */
    public record ActivityDay(DayOfWeek day, String open, String close) {
    }

    public record SettingsView(int microCountSize, long adjustmentApprovalCents, long refundApprovalCents,
                               long cancellationApprovalCents, long alertAdjustmentCents,
                               long alertCancellationCents, long alertCashVarianceCents, int pinFailureAlertCount,
                               boolean blindReceiving, Separation receivingSeparation,
                               List<ActivityDay> activityHours, boolean webhookEnabled, String webhookUrl,
                               boolean webhookSecretSet, Instant updatedAt, long version) {
    }

    /** @param webhookSecret {@code null} keeps the stored secret, a value replaces it */
    public record SettingsUpdate(int microCountSize, long adjustmentApprovalCents, long refundApprovalCents,
                                 long cancellationApprovalCents, long alertAdjustmentCents,
                                 long alertCancellationCents, long alertCashVarianceCents, int pinFailureAlertCount,
                                 boolean blindReceiving, Separation receivingSeparation,
                                 List<ActivityDay> activityHours, boolean webhookEnabled, String webhookUrl,
                                 String webhookSecret, long version) {
    }

    /** What the rules read: values, parsed hours, the secrets. */
    public record Effective(Values values, ActivityHours hours, String webhookSecret, String selectionSalt) {

        public FourEyesPolicy.Thresholds thresholds() {
            return new FourEyesPolicy.Thresholds(values.adjustmentApprovalCents(), values.refundApprovalCents(),
                    values.cancellationApprovalCents());
        }

        public boolean webhookActive() {
            return values.webhookEnabled() && values.webhookUrl() != null && webhookSecret != null;
        }
    }

    private final ControlSettingsRepository settings;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final String defaultHours;

    public ControlSettingsService(ControlSettingsRepository settings, AuditTrail audit, JdbcClient jdbc,
                                  ObjectMapper json, Clock clock) {
        this.settings = settings;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.defaultHours = write(days(ActivityHours.defaults()));
    }

    /** Defaults of a pharmacy that never saved its settings. */
    public Values defaults() {
        return new Values(6, 10_000, 5_000, 5_000, 25_000, 10_000, 2_000, 3, false, Separation.OFF, defaultHours,
                false, null);
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Effective effective() {
        return settings.findById(TenantContext.requireTenant().value())
                .map(this::effective)
                .orElseGet(() -> new Effective(defaults(), ActivityHours.defaults(), null, null));
    }

    /** The stored row, created with the defaults and a fresh salt when missing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Effective ensure() {
        UUID tenant = TenantContext.requireTenant().value();
        Values d = defaults();
        jdbc.sql("""
                        INSERT INTO control_settings (tenant_id, activity_hours, selection_salt, updated_at)
                        VALUES (:tenant, :hours::jsonb, :salt, now())
                        ON CONFLICT (tenant_id) DO NOTHING""")
                .param("tenant", tenant)
                .param("hours", d.activityHoursJson())
                .param("salt", salt())
                .update();
        return effective(settings.findById(tenant).orElseThrow());
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        return settings.findById(TenantContext.requireTenant().value())
                .map(entity -> view(entity.values(), entity.webhookSecret() != null, entity.updatedAt(),
                        entity.version()))
                .orElseGet(() -> view(defaults(), false, null, 0));
    }

    @Transactional
    public SettingsView update(SettingsUpdate update) {
        var user = TenantContext.requireTenantUser();
        ensure();
        ControlSettingsEntity entity = settings.findById(user.tenant().value()).orElseThrow();
        if (entity.version() != update.version()) {
            throw new OptimisticLockingFailureException("Control settings changed since they were read");
        }
        ActivityHours hours = hours(update.activityHours());
        String url = blankToNull(update.webhookUrl());
        if (url != null) {
            validateUrl(url);
        }
        String secret = update.webhookSecret() == null ? entity.webhookSecret() : blankToNull(update.webhookSecret());
        if (secret != null && secret.length() < 16) {
            throw new BusinessRuleException("webhook-secret-too-short",
                    "Le secret partagé du webhook doit compter au moins 16 caractères");
        }
        if (update.webhookEnabled() && (url == null || secret == null)) {
            throw new BusinessRuleException("webhook-incomplete",
                    "Pour activer le webhook, indiquez son adresse et un secret partagé");
        }
        if (update.microCountSize() < 5 || update.microCountSize() > 10) {
            throw new BusinessRuleException("micro-count-size", "Le contrôle du jour porte sur 5 à 10 produits");
        }
        if (update.pinFailureAlertCount() < 2 || update.pinFailureAlertCount() > 10) {
            throw new BusinessRuleException("pin-failure-count", "Le nombre d'échecs de PIN doit être entre 2 et 10");
        }
        if (update.adjustmentApprovalCents() < 0 || update.refundApprovalCents() < 0
                || update.cancellationApprovalCents() < 0 || update.alertAdjustmentCents() < 0
                || update.alertCancellationCents() < 0 || update.alertCashVarianceCents() < 0) {
            throw new BusinessRuleException("negative-threshold", "Un seuil ne peut pas être négatif");
        }
        boolean secretChanged = update.webhookSecret() != null;
        entity.apply(new Values(update.microCountSize(), update.adjustmentApprovalCents(),
                update.refundApprovalCents(), update.cancellationApprovalCents(), update.alertAdjustmentCents(),
                update.alertCancellationCents(), update.alertCashVarianceCents(), update.pinFailureAlertCount(),
                update.blindReceiving(), update.receivingSeparation(), write(days(hours)), update.webhookEnabled(),
                url), user.userId(), clock.instant());
        entity.setWebhookSecret(secret);
        settings.saveAndFlush(entity);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("microCountSize", Integer.toString(update.microCountSize()));
        details.put("adjustmentApprovalCents", Long.toString(update.adjustmentApprovalCents()));
        details.put("refundApprovalCents", Long.toString(update.refundApprovalCents()));
        details.put("cancellationApprovalCents", Long.toString(update.cancellationApprovalCents()));
        details.put("alertAdjustmentCents", Long.toString(update.alertAdjustmentCents()));
        details.put("alertCancellationCents", Long.toString(update.alertCancellationCents()));
        details.put("alertCashVarianceCents", Long.toString(update.alertCashVarianceCents()));
        details.put("pinFailureAlertCount", Integer.toString(update.pinFailureAlertCount()));
        details.put("blindReceiving", Boolean.toString(update.blindReceiving()));
        details.put("receivingSeparation", update.receivingSeparation().name());
        details.put("activityHours", write(days(hours)));
        details.put("webhookEnabled", Boolean.toString(update.webhookEnabled()));
        details.put("webhookUrl", String.valueOf(url));
        details.put("webhookSecretChanged", Boolean.toString(secretChanged));
        audit.record("CONTROL_SETTINGS_UPDATED", "PHARMACY", user.tenant().value(), details);
        return view(entity.values(), secret != null, entity.updatedAt(), entity.version());
    }

    private Effective effective(ControlSettingsEntity entity) {
        Values values = entity.values();
        return new Effective(values, ActivityHours.of(read(values.activityHoursJson()).stream()
                .map(d -> new ActivityHours.Day(d.day(), LocalTime.parse(d.open()), LocalTime.parse(d.close())))
                .toList()), entity.webhookSecret(), entity.selectionSalt());
    }

    private SettingsView view(Values v, boolean secretSet, Instant updatedAt, long version) {
        return new SettingsView(v.microCountSize(), v.adjustmentApprovalCents(), v.refundApprovalCents(),
                v.cancellationApprovalCents(), v.alertAdjustmentCents(), v.alertCancellationCents(),
                v.alertCashVarianceCents(), v.pinFailureAlertCount(), v.blindReceiving(), v.receivingSeparation(),
                read(v.activityHoursJson()), v.webhookEnabled(), v.webhookUrl(), secretSet, updatedAt, version);
    }

    private static ActivityHours hours(List<ActivityDay> days) {
        if (days == null || days.isEmpty()) {
            throw new BusinessRuleException("activity-hours", "Indiquez au moins un jour d'ouverture");
        }
        try {
            return ActivityHours.of(days.stream()
                    .map(d -> new ActivityHours.Day(d.day(), LocalTime.parse(d.open()), LocalTime.parse(d.close())))
                    .toList());
        } catch (DateTimeParseException | IllegalArgumentException | NullPointerException e) {
            throw new BusinessRuleException("activity-hours",
                    "Heures d'activité invalides : chaque jour ouvert va d'une heure d'ouverture à une heure de "
                            + "fermeture plus tardive (HH:mm)");
        }
    }

    private static List<ActivityDay> days(ActivityHours hours) {
        return hours.lines().stream()
                .map(d -> new ActivityDay(d.day(), d.open().toString(), d.close().toString()))
                .toList();
    }

    private static void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException(url);
            }
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("webhook-url", "Adresse du webhook invalide : http(s)://…");
        }
    }

    private String write(List<ActivityDay> days) {
        try {
            return json.writeValueAsString(days);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize activity hours", e);
        }
    }

    private List<ActivityDay> read(String text) {
        try {
            return json.readValue(text, DAYS);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt activity hours", e);
        }
    }

    private static String salt() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
