package com.pharmacore.sales.application;

import com.pharmacore.sales.domain.PaymentSmsParser;
import com.pharmacore.sales.domain.PaymentSmsParser.CustomPattern;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * How the pharmacy receives its payment confirmations: the ingest token presented by its phone
 * (forwarded SMS) and by operator webhooks, whether a unique match confirms the payment by
 * itself, and the pharmacy's own SMS patterns. The token is stored hashed and shown once.
 */
@Service
public class PaymentChannelSettingsService {

    private static final TypeReference<List<CustomPattern>> PATTERNS = new TypeReference<>() {
    };
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int TOKEN_LENGTH = 32;

    public record Values(boolean autoConfirm, int matchWindowHours, List<CustomPattern> patterns) {

        public Values {
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
        }
    }

    public record View(Values values, boolean tokenSet, String tokenHint, Instant tokenCreatedAt, Instant updatedAt) {
    }

    /** The clear token, returned once at creation. */
    public record IssuedToken(String token, String hint, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PaymentChannelSettingsService(JdbcClient jdbc, ObjectMapper json, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    public static Values defaults() {
        return new Values(true, 48, List.of());
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Values effective() {
        return load().map(Row::values).orElseGet(PaymentChannelSettingsService::defaults);
    }

    @Transactional(readOnly = true)
    public View view() {
        return load().map(row -> new View(row.values(), row.tokenHash() != null, row.tokenHint(), row.tokenCreatedAt(),
                        row.updatedAt()))
                .orElseGet(() -> new View(defaults(), false, null, null, null));
    }

    /** Constant-time comparison of the presented token with the stored hash. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean verify(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return load().map(Row::tokenHash)
                .map(stored -> MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                        hash(token.trim()).getBytes(StandardCharsets.US_ASCII)))
                .orElse(false);
    }

    @Transactional
    public View update(Values values) {
        List<String> errors = PaymentSmsParser.validate(values.patterns());
        if (!errors.isEmpty()) {
            throw new BusinessRuleException("invalid-pattern", String.join(" ; ", errors));
        }
        var user = TenantContext.requireTenantUser();
        jdbc.sql("""
                        INSERT INTO payment_channel_settings (tenant_id, auto_confirm, match_window_hours, patterns,
                                                              updated_by, updated_at)
                        VALUES (:tenant, :auto, :window, :patterns::jsonb, :by, :at)
                        ON CONFLICT (tenant_id) DO UPDATE
                            SET auto_confirm = EXCLUDED.auto_confirm, match_window_hours = EXCLUDED.match_window_hours,
                                patterns = EXCLUDED.patterns, updated_by = EXCLUDED.updated_by,
                                updated_at = EXCLUDED.updated_at, version = payment_channel_settings.version + 1""")
                .param("tenant", user.tenant().value())
                .param("auto", values.autoConfirm())
                .param("window", Math.clamp(values.matchWindowHours(), 1, 720))
                .param("patterns", json.writeValueAsString(values.patterns()))
                .param("by", user.userId())
                .param("at", Timestamp.from(clock.instant()))
                .update();
        audit.record("PAYMENT_CHANNEL_UPDATED", "PAYMENT_CHANNEL", user.tenant().value(), Map.of(
                "autoConfirm", Boolean.toString(values.autoConfirm()),
                "patterns", Integer.toString(values.patterns().size())));
        return view();
    }

    /** A new token replaces the previous one at once: the phone and the webhooks must be updated. */
    @Transactional
    public IssuedToken issueToken() {
        var user = TenantContext.requireTenantUser();
        StringBuilder token = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            token.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String clear = token.toString();
        String hint = clear.substring(TOKEN_LENGTH - 4);
        Instant now = clock.instant();
        Values current = effective();
        jdbc.sql("""
                        INSERT INTO payment_channel_settings (tenant_id, ingest_token_hash, ingest_token_hint, token_created_at,
                                                              auto_confirm, match_window_hours, patterns, updated_by, updated_at)
                        VALUES (:tenant, :hash, :hint, :at, :auto, :window, :patterns::jsonb, :by, :at)
                        ON CONFLICT (tenant_id) DO UPDATE
                            SET ingest_token_hash = EXCLUDED.ingest_token_hash, ingest_token_hint = EXCLUDED.ingest_token_hint,
                                token_created_at = EXCLUDED.token_created_at, updated_by = EXCLUDED.updated_by,
                                updated_at = EXCLUDED.updated_at, version = payment_channel_settings.version + 1""")
                .param("tenant", user.tenant().value())
                .param("hash", hash(clear))
                .param("hint", hint)
                .param("at", Timestamp.from(now))
                .param("auto", current.autoConfirm())
                .param("window", current.matchWindowHours())
                .param("patterns", json.writeValueAsString(current.patterns()))
                .param("by", user.userId())
                .update();
        audit.record("PAYMENT_CHANNEL_TOKEN_ISSUED", "PAYMENT_CHANNEL", user.tenant().value(), Map.of("hint", hint));
        return new IssuedToken(clear, hint, now);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Row(Values values, String tokenHash, String tokenHint, Instant tokenCreatedAt, Instant updatedAt) {
    }

    private Optional<Row> load() {
        UUID tenant = TenantContext.requireTenant().value();
        return jdbc.sql("""
                        SELECT ingest_token_hash, ingest_token_hint, token_created_at, auto_confirm, match_window_hours,
                               patterns::text AS patterns, updated_at
                        FROM payment_channel_settings WHERE tenant_id = :tenant""")
                .param("tenant", tenant)
                .query((rs, rowNum) -> new Row(
                        new Values(rs.getBoolean("auto_confirm"), rs.getInt("match_window_hours"),
                                json.readValue(rs.getString("patterns"), PATTERNS)),
                        rs.getString("ingest_token_hash"), rs.getString("ingest_token_hint"),
                        rs.getTimestamp("token_created_at") == null ? null : rs.getTimestamp("token_created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }
}
