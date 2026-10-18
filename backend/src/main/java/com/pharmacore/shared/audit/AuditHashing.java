package com.pharmacore.shared.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic serialization and SHA-256 chaining of audit events. Any later modification of a
 * row (even by a DBA bypassing the append-only trigger) breaks every subsequent hash.
 */
public final class AuditHashing {

    public static final String GENESIS = "0".repeat(64);
    private static final char SEPARATOR = '\u001F';

    private AuditHashing() {
    }

    /** PostgreSQL stores microseconds: hash exactly what will be read back. */
    public static Instant normalize(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** JSON object with keys sorted, values as strings, minimal escaping. */
    public static String canonicalDetails(Map<String, String> details) {
        var sorted = new TreeMap<>(details);
        var json = new StringBuilder("{");
        sorted.forEach((key, value) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            appendJsonString(json, key);
            json.append(':');
            if (value == null) {
                json.append("null");
            } else {
                appendJsonString(json, value);
            }
        });
        return json.append('}').toString();
    }

    public static String hash(UUID tenantId, long sequenceNo, Instant occurredAt, String actorType, String actorId,
                              String action, String resourceType, String resourceId, String details,
                              String correlationId, String previousHash) {
        String payload = String.join(String.valueOf(SEPARATOR),
                previousHash,
                tenantId.toString(),
                Long.toString(sequenceNo),
                normalize(occurredAt).toString(),
                actorType,
                actorId,
                action,
                resourceType,
                resourceId == null ? "" : resourceId,
                details,
                correlationId);
        return sha256(payload);
    }

    public static String hash(AuditRecord record) {
        return hash(record.tenantId(), record.sequenceNo(), record.occurredAt(), record.actorType(), record.actorId(),
                record.action(), record.resourceType(), record.resourceId(), record.details(),
                record.correlationId(), record.previousHash());
    }

    private static String sha256(String payload) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandated by the Java platform", e);
        }
    }

    private static void appendJsonString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
