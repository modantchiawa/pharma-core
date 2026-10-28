package com.pharmacore.safety.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalisation of active substance names as written in the national catalog ("Amoxicilline +
 * acide clavulanique", "DICLOFENAC SODIQUE") and of the rule tokens of the referential (a DCI
 * such as {@code ACIDE ACETYLSALICYLIQUE}, or an ATC class prefix such as {@code ATC:M01A}).
 */
public final class SubstanceNames {

    public static final String ATC_PREFIX = "ATC:";
    private static final int MIN_COMPONENT_LENGTH = 3;

    private SubstanceNames() {
    }

    /** Upper case, accents removed, punctuation and multiple spaces collapsed. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String stripped = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toUpperCase(Locale.ROOT)
                .replace('’', '\'')
                .replaceAll("[^A-Z0-9:+/,()' -]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** The substances of a (possibly combined) DCI, normalised, in order and without duplicates. */
    public static List<String> components(String dci) {
        String normalized = normalize(dci);
        if (normalized.isEmpty()) {
            return List.of();
        }
        Set<String> parts = new LinkedHashSet<>();
        for (String part : normalized.split("[+/,]")) {
            String cleaned = part.replaceAll("\\(.*?\\)", "").replaceAll("\\s+", " ").trim();
            if (cleaned.length() >= MIN_COMPONENT_LENGTH) {
                parts.add(cleaned);
            }
        }
        return List.copyOf(parts);
    }

    /** Every leading word sequence of a component: "ACIDE ACETYLSALICYLIQUE" gives both tokens. */
    public static List<String> prefixes(String component) {
        List<String> prefixes = new ArrayList<>();
        String[] words = component.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
            prefixes.add(current.toString());
        }
        return prefixes;
    }

    public static boolean isAtcToken(String token) {
        return token != null && token.startsWith(ATC_PREFIX);
    }

    /**
     * A rule token matches a product when it names one of its substances (exactly, or as the
     * leading words: "DICLOFENAC" covers "DICLOFENAC SODIQUE") or an ATC class the product belongs to.
     */
    public static boolean matches(String token, List<String> components, String atcCode) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (isAtcToken(token)) {
            String wanted = token.substring(ATC_PREFIX.length()).trim().toUpperCase(Locale.ROOT);
            return atcCode != null && !wanted.isEmpty() && normalize(atcCode).startsWith(wanted);
        }
        return components.stream().anyMatch(component -> matchesComponent(token, component));
    }

    public static boolean matchesComponent(String token, String component) {
        return component.equals(token) || component.startsWith(token + " ");
    }
}
