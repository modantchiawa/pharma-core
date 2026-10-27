package com.pharmacore.inventory.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Short code of a storage location, printed on shelf labels and shown at the counter ("A3",
 * "FRIGO", "TIROIR 2"). Codes are compared in their canonical form: upper case, no accents,
 * single spaces, at most {@value #MAX_LENGTH} characters among letters, digits, space and
 * {@code . _ / -}.
 */
public final class LocationCode {

    public static final int MAX_LENGTH = 16;

    private static final Pattern VALID = Pattern.compile("[A-Z0-9][A-Z0-9 ._/-]{0," + (MAX_LENGTH - 1) + "}");
    private static final Pattern DISALLOWED = Pattern.compile("[^A-Z0-9 ._/-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private LocationCode() {
    }

    /**
     * Canonical form of a code typed by the user.
     *
     * @throws IllegalArgumentException when the code is empty, too long or has other characters
     */
    public static String normalize(String raw) {
        String code = raw == null ? "" : canonical(raw);
        if (!VALID.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid location code: " + raw);
        }
        return code;
    }

    /**
     * Code derived from a free-text location ("Rayon A · étagère 3" gives "RAYON A ETAGERE 3",
     * cut to {@value #MAX_LENGTH} characters); empty when the text has no letter nor digit. Same
     * rule as the V14 migration that took over the former free-text column.
     */
    public static Optional<String> fromLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String code = SPACES.matcher(DISALLOWED.matcher(canonical(label)).replaceAll(" ")).replaceAll(" ").strip();
        code = code.substring(0, Math.min(code.length(), MAX_LENGTH)).strip();
        return VALID.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }

    private static String canonical(String raw) {
        String stripped = Normalizer.normalize(raw.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return SPACES.matcher(stripped.toUpperCase(Locale.ROOT)).replaceAll(" ");
    }
}
