package com.pharmacore.followup.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Phone numbers as typed at the counter ("90 12 34 56", "+227 90 12 34 56", "00227...") turned
 * into the international digits WhatsApp and SMS gateways expect, with the pharmacy's country
 * as the default calling code.
 */
public final class PhoneNumbers {

    private static final Map<String, String> CALLING_CODES = Map.ofEntries(
            Map.entry("NE", "227"), Map.entry("SN", "221"), Map.entry("ML", "223"), Map.entry("BF", "226"),
            Map.entry("CI", "225"), Map.entry("TG", "228"), Map.entry("BJ", "229"), Map.entry("GN", "224"),
            Map.entry("CM", "237"), Map.entry("TD", "235"), Map.entry("NG", "234"), Map.entry("GH", "233"),
            Map.entry("MR", "222"), Map.entry("GA", "241"), Map.entry("CG", "242"), Map.entry("CD", "243"),
            Map.entry("MA", "212"), Map.entry("DZ", "213"), Map.entry("TN", "216"), Map.entry("FR", "33"));

    private PhoneNumbers() {
    }

    public static String callingCode(String countryCode) {
        return CALLING_CODES.getOrDefault(countryCode == null ? "" : countryCode.toUpperCase(), "227");
    }

    /** International digits without "+", or empty when the number cannot be a phone number. */
    public static Optional<String> international(String phone, String countryCode) {
        if (phone == null) {
            return Optional.empty();
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        boolean explicit = digits.startsWith("+") || digits.startsWith("00");
        digits = digits.replace("+", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (!explicit) {
            String code = callingCode(countryCode);
            // West African numbers carry no trunk prefix (a leading 0 is part of the number in
            // Côte d'Ivoire and Benin); France does.
            if ("33".equals(code) && digits.startsWith("0")) {
                digits = digits.substring(1);
            }
            if (!digits.startsWith(code) || digits.length() < code.length() + 8) {
                digits = code + digits;
            }
        }
        return digits.length() >= 10 && digits.length() <= 15 ? Optional.of(digits) : Optional.empty();
    }

    /** The "click to chat" link, text pre-filled: the staff member only presses send. */
    public static String whatsappLink(String internationalDigits, String body) {
        return "https://wa.me/" + internationalDigits + "?text="
                + URLEncoder.encode(body, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
