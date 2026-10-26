package com.pharmacore.importing.application;

import com.pharmacore.catalog.domain.Cip13;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Products of the previous software that carry no CIP or EAN (parapharmacy, local preparations,
 * internal references) get a stable 13-digit code in the GS1 restricted range (prefix 200), derived
 * from their label: re-importing the same file never creates a second product.
 */
final class InternalCodes {

    private static final String PREFIX = "200";

    private InternalCodes() {
    }

    static String forLabel(String label) {
        String normalized = label.trim().replaceAll("\\s+", " ").toUpperCase();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFF);
            }
            String body = String.format("%09d", Math.floorMod(value, 1_000_000_000L));
            String first12 = PREFIX + body;
            return first12 + Cip13.checkDigit(first12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
