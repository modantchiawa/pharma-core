package com.pharmacore.subscription.domain;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Compact signed license: {@code PCL1.<kid>.<claims>.<signature>}, claims and signature in
 * base64url. The claims are {@code key=value} lines in a fixed order, so the signed bytes never
 * depend on a JSON library; the signature (Ed25519) covers everything before it.
 */
public final class LicenseCodec {

    static final String VERSION = "PCL1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private LicenseCodec() {
    }

    public static String sign(LicenseClaims claims, String kid, PrivateKey key) {
        String signed = VERSION + "." + kid + "." + ENCODER.encodeToString(encode(claims).getBytes(StandardCharsets.UTF_8));
        return signed + "." + ENCODER.encodeToString(LicenseKeys.sign(key, signed.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * @param keys the trusted public keys by key id
     * @throws LicenseRejectedException MALFORMED, UNKNOWN_KEY or BAD_SIGNATURE
     */
    public static LicenseClaims verify(String token, Function<String, Optional<PublicKey>> keys) {
        String[] parts = split(token);
        PublicKey key = keys.apply(parts[1])
                .orElseThrow(() -> new LicenseRejectedException(LicenseRejectedException.Reason.UNKNOWN_KEY));
        byte[] signature;
        byte[] payload;
        try {
            signature = DECODER.decode(parts[3]);
            payload = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new LicenseRejectedException(LicenseRejectedException.Reason.MALFORMED);
        }
        String signed = parts[0] + "." + parts[1] + "." + parts[2];
        if (!LicenseKeys.verify(key, signed.getBytes(StandardCharsets.US_ASCII), signature)) {
            throw new LicenseRejectedException(LicenseRejectedException.Reason.BAD_SIGNATURE);
        }
        return decode(new String(payload, StandardCharsets.UTF_8));
    }

    private static String[] split(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || parts[1].isBlank()) {
            throw new LicenseRejectedException(LicenseRejectedException.Reason.MALFORMED);
        }
        return parts;
    }

    static String encode(LicenseClaims claims) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("tid", claims.tenantId().toString());
        fields.put("slug", claims.slug());
        fields.put("plan", claims.plan());
        fields.put("terminals", Integer.toString(claims.maxTerminals()));
        fields.put("status", claims.status().name());
        fields.put("paid", text(claims.paidUntil()));
        fields.put("grace", text(claims.graceUntil()));
        fields.put("suspended", text(claims.suspendedAt()));
        fields.put("iat", text(claims.issuedAt()));
        fields.put("exp", text(claims.validUntil()));
        fields.put("serial", Long.toString(claims.serial()));
        var out = new StringBuilder();
        fields.forEach((name, value) -> {
            if (value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Line break in license field " + name);
            }
            out.append(name).append('=').append(value).append('\n');
        });
        return out.toString();
    }

    static LicenseClaims decode(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                throw new LicenseRejectedException(LicenseRejectedException.Reason.MALFORMED);
            }
            fields.put(line.substring(0, equals), line.substring(equals + 1));
        }
        try {
            return new LicenseClaims(
                    UUID.fromString(required(fields, "tid")),
                    required(fields, "slug"),
                    required(fields, "plan"),
                    Integer.parseInt(required(fields, "terminals")),
                    LicenseClaims.Status.valueOf(required(fields, "status")),
                    instant(fields.get("paid")),
                    instant(fields.get("grace")),
                    instant(fields.get("suspended")),
                    instant(required(fields, "iat")),
                    instant(fields.get("exp")),
                    Long.parseLong(required(fields, "serial")));
        } catch (IllegalArgumentException | DateTimeParseException | NullPointerException e) {
            throw new LicenseRejectedException(LicenseRejectedException.Reason.MALFORMED);
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new LicenseRejectedException(LicenseRejectedException.Reason.MALFORMED);
        }
        return value;
    }

    private static String text(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    private static Instant instant(String text) {
        return text == null || text.isEmpty() ? null : Instant.parse(text);
    }
}
