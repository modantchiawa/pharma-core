package com.pharmacore.control.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature of an outgoing webhook: {@code sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>}
 * in the {@value #SIGNATURE_HEADER} header, the Unix timestamp (seconds) in
 * {@value #TIMESTAMP_HEADER}. The receiver recomputes it with the shared secret and rejects old
 * timestamps, so a captured request can neither be forged nor replayed later.
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "X-PharmaCore-Signature";
    public static final String TIMESTAMP_HEADER = "X-PharmaCore-Timestamp";
    public static final String EVENT_HEADER = "X-PharmaCore-Event";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time comparison, for receivers written in Java (and the tests). */
    public static boolean verify(String secret, long timestampSeconds, String body, String signature) {
        return signature != null && MessageDigest.isEqual(sign(secret, timestampSeconds, body)
                .getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
