package com.pharmacore.procurement.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds which ordered product an invoice line delivers when its code is the wholesaler's own
 * (Laborex, Copharni… print internal codes in the CIP column): by the designation, among the
 * products of the order only. A match must be unique, else the pharmacist decides. Pure function.
 */
public final class InvoiceMatching {

    /** Shorter designations are too vague to be matched by their beginning. */
    static final int MIN_PREFIX = 8;

    private InvoiceMatching() {
    }

    /** An ordered product and its designation in the pharmacy's catalogue. */
    public record Ordered(UUID productId, String designation) {
    }

    public static Optional<UUID> byDesignation(String designation, List<Ordered> ordered) {
        String wanted = key(designation);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> exact = ordered.stream().filter(o -> key(o.designation()).equals(wanted))
                .map(Ordered::productId).distinct().toList();
        if (exact.size() == 1) {
            return Optional.of(exact.getFirst());
        }
        if (!exact.isEmpty()) {
            return Optional.empty();
        }
        // "AMOXICILLINE 500MG GEL B/12" on the invoice, "AMOXICILLINE 500MG GEL B/12 BIOGARAN" in the catalogue
        List<UUID> prefix = ordered.stream()
                .filter(o -> {
                    String candidate = key(o.designation());
                    String shorter = candidate.length() < wanted.length() ? candidate : wanted;
                    String longer = shorter == candidate ? wanted : candidate;
                    return shorter.length() >= MIN_PREFIX && longer.startsWith(shorter);
                })
                .map(Ordered::productId).distinct().toList();
        return prefix.size() == 1 ? Optional.of(prefix.getFirst()) : Optional.empty();
    }

    /** Upper case, no accents, letters and digits separated by single spaces: "B/6" and "B 6" meet. */
    static String key(String designation) {
        if (designation == null) {
            return "";
        }
        return Normalizer.normalize(designation, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }
}
