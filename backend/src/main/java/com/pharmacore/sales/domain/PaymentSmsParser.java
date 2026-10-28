package com.pharmacore.sales.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads the confirmation SMS of the mobile money operators of the region ("Vous avez reçu 5 000
 * FCFA de 90123456. Trans ID: PP251027.1432.A12345…"). The pharmacy can add its own patterns
 * (named groups {@code amount}, {@code reference}, {@code phone}) when an operator changes its
 * wording; the generic reading below covers the usual messages without any setting.
 */
public final class PaymentSmsParser {

    /** A pattern the pharmacy declared for one operator; a null provider applies to every message. */
    public record CustomPattern(String provider, String regex) {
    }

    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![A-Z0-9])(\\d{1,3}(?:[ .\\u202f\\u00a0]?\\d{3})*(?:[.,]\\d{1,2})?)\\s?(?:F\\s?CFA|FCFA|XOF|CFA|F(?![A-Z]))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:TRANS(?:ACTION)?\\s?ID|TXN\\s?ID|TXNID|TRANSACTION|REF(?:ERENCE)?|ID\\s?TRANSACTION|N[°O]\\s?(?:DE\\s)?(?:TRANSACTION|TXN)?|ID)"
                    + "\\s*[:.=]?\\s*([A-Z0-9][A-Z0-9.\\-_/]{4,39})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?2\\d{2}[ .]?)?(\\d{2}[ .]?\\d{2}[ .]?\\d{2}[ .]?\\d{2})(?!\\d)");
    private static final Pattern INCOMING = Pattern.compile(
            "(RECU|RECUE|RECEIVED|RECEVEZ|CREDITE|CREDITED|VERSEMENT|VOUS A ENVOYE|A ENVOYE|DEPOT|PAIEMENT DE|PAYMENT FROM|TRANSFERT DE)");
    private static final Pattern OUTGOING = Pattern.compile(
            "(VOUS AVEZ (ENVOYE|TRANSFERE|PAYE|RETIRE|ACHETE)|RETRAIT|ENVOI DE|TRANSFERT VERS|SENT TO|WITHDRAW|DEBITE|VOTRE SOLDE EST|ECHEC|FAILED|ANNUL)");

    private static final Map<String, List<String>> PROVIDER_KEYWORDS = Map.of(
            "AIRTEL_MONEY", List.of("AIRTEL"),
            "MOOV_MONEY", List.of("MOOV", "FLOOZ"),
            "ORANGE_MONEY", List.of("ORANGE"),
            "ZAMANI_CASH", List.of("ZAMANI"),
            "MYNITA", List.of("NITA"),
            "WAVE", List.of("WAVE"));

    private PaymentSmsParser() {
    }

    /**
     * @param sender   the SMS sender (short code or name), may be null
     * @param text     the message
     * @param patterns the pharmacy's own patterns, tried first
     */
    public static PaymentNotice parse(String sender, String text, List<CustomPattern> patterns) {
        if (text == null || text.isBlank()) {
            return PaymentNotice.UNREADABLE;
        }
        String provider = provider(sender, text);
        String normalized = normalize(text);
        boolean outgoing = OUTGOING.matcher(normalized).find();
        boolean incoming = !outgoing && INCOMING.matcher(normalized).find();
        for (CustomPattern custom : patterns == null ? List.<CustomPattern>of() : patterns) {
            if (custom.provider() != null && !custom.provider().isBlank() && !custom.provider().equals(provider)) {
                continue;
            }
            PaymentNotice read = applyCustom(custom, provider, text, incoming || !outgoing);
            if (read != null) {
                return read;
            }
        }
        Long amount = amount(text);
        String reference = reference(text);
        String phone = phone(text);
        if (amount == null && reference == null) {
            return new PaymentNotice(provider, null, null, phone, false);
        }
        return new PaymentNotice(provider, amount, reference, phone, incoming);
    }

    /** Operator from the sender name or the wording; null when nothing says. */
    public static String provider(String sender, String text) {
        String haystack = normalize((sender == null ? "" : sender) + " " + (text == null ? "" : text));
        for (var entry : PROVIDER_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (haystack.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** Amount in minor units of a no-decimal currency (francs): "5 000 FCFA" gives 5000. */
    public static Long amount(String text) {
        Matcher matcher = AMOUNT.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group(1).replaceAll("[ .\\u202f\\u00a0]", "").replace(',', '.');
            try {
                double value = Double.parseDouble(digits);
                if (value > 0) {
                    return Math.round(value);
                }
            } catch (NumberFormatException ignored) {
                // a number too odd to be an amount: keep looking
            }
        }
        return null;
    }

    public static String reference(String text) {
        Matcher matcher = REFERENCE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT).replaceAll("[.\\-_/]+$", "");
            // a bare number of 8 digits is a phone number, not a reference
            if (!candidate.matches("\\d{8,9}") && candidate.length() >= 5) {
                return candidate;
            }
        }
        return null;
    }

    public static String phone(String text) {
        Matcher matcher = PHONE.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group(0).replaceAll("[^0-9]", "");
            if (digits.length() >= 8 && digits.length() <= 12) {
                return digits;
            }
        }
        return null;
    }

    private static PaymentNotice applyCustom(CustomPattern custom, String provider, String text, boolean incoming) {
        try {
            Matcher matcher = Pattern.compile(custom.regex(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
            if (!matcher.find()) {
                return null;
            }
            Long amount = group(matcher, "amount") == null ? null : amount(group(matcher, "amount") + " F");
            String reference = group(matcher, "reference");
            String phone = group(matcher, "phone") == null ? null : group(matcher, "phone").replaceAll("[^0-9]", "");
            return new PaymentNotice(custom.provider() == null || custom.provider().isBlank() ? provider
                    : custom.provider(), amount, reference == null ? null : reference.toUpperCase(Locale.ROOT),
                    phone, incoming && amount != null);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static String group(Matcher matcher, String name) {
        try {
            String value = matcher.group(name);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    /** Validates a pattern typed by the pharmacy: compiles, and names at least the amount. */
    public static List<String> validate(List<CustomPattern> patterns) {
        List<String> errors = new ArrayList<>();
        for (CustomPattern custom : patterns) {
            try {
                Pattern.compile(custom.regex());
                if (!custom.regex().contains("(?<amount>")) {
                    errors.add("Le motif « " + custom.regex() + " » doit nommer le groupe amount");
                }
            } catch (PatternSyntaxException e) {
                errors.add("Motif invalide : " + e.getDescription());
            }
        }
        return errors;
    }
}
