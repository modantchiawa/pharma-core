package com.pharmacore.control.domain;

import java.util.Set;

/**
 * Amounts in alert texts and explanations, which leave the application (webhook, SMS): plain
 * spaces between thousands, F CFA without decimals, other currencies with two.
 */
public final class Amounts {

    private static final Set<String> NO_DECIMALS = Set.of("XOF", "XAF", "GNF");

    private Amounts() {
    }

    public static String format(long minorUnits, String currency) {
        String code = currency == null ? "XOF" : currency;
        boolean whole = NO_DECIMALS.contains(code);
        long units = whole ? minorUnits : minorUnits / 100;
        String sign = minorUnits < 0 ? "-" : "";
        String grouped = group(Math.abs(units));
        if (whole) {
            return sign + grouped + (code.equals("XOF") || code.equals("XAF") ? " F CFA" : " " + code);
        }
        long cents = Math.abs(minorUnits % 100);
        return sign + grouped + "," + (cents < 10 ? "0" : "") + cents + " " + code;
    }

    private static String group(long value) {
        String digits = Long.toString(value);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
