package com.pharmacore.importing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tolerant parsers for the values found in pharmacy exports: amounts with spaces, commas or a
 * currency suffix, percentages written "18", "18 %" or "0,18", dates in French, ISO or Excel
 * serial form, month-only expiry dates ("09/2027") taken as the last day of the month.
 */
public final class ValueParsers {

    private static final Pattern DIGITS13 = Pattern.compile("\\d{13}");
    private static final Pattern NOT_NUMERIC = Pattern.compile("[^0-9,.\\-]");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("dd/MM/yy"));
    private static final List<DateTimeFormatter> MONTH_FORMATS = List.of(
            DateTimeFormatter.ofPattern("MM/yyyy"),
            DateTimeFormatter.ofPattern("M/yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM"),
            DateTimeFormatter.ofPattern("MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyyMM"),
            DateTimeFormatter.ofPattern("MM/yy"));
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private ValueParsers() {
    }

    public static String text(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * A barcode-like code (CIP13, EAN13, EAN8, UPC): digits only, 8 to 13 of them, left-padded with
     * zeros to 13. Anything with letters or fewer digits is an internal reference, not a GTIN.
     */
    public static Optional<String> gtin13(String raw) {
        if (raw == null || !raw.trim().matches("[\\d\\s.\\-]+")) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 13) {
            return Optional.empty();
        }
        String padded = "0".repeat(13 - digits.length()) + digits;
        return DIGITS13.matcher(padded).matches() ? Optional.of(padded) : Optional.empty();
    }

    /**
     * Amount in minor units of the currency: whole francs for XOF ({@code currencyScale} 0),
     * cents for EUR (2). "1 250", "1250 FCFA", "1.250", "1250,00" → 1250 in XOF; "12,50" → 1250 in EUR.
     */
    public static Optional<Long> cents(String raw, int currencyScale) {
        return decimal(raw).map(value -> value.movePointRight(currencyScale).setScale(0, RoundingMode.HALF_UP)
                .longValueExact());
    }

    public static Optional<BigDecimal> decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = NOT_NUMERIC.matcher(raw.replace(' ', ' ').replace(' ', ' ')).replaceAll("");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return Optional.empty();
        }
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        String normalized;
        if (lastComma >= 0 && lastDot >= 0) {
            // the last separator is the decimal one, the other groups thousands
            normalized = lastComma > lastDot
                    ? cleaned.replace(".", "").replace(',', '.')
                    : cleaned.replace(",", "");
        } else if (lastComma >= 0) {
            // "1,250" with exactly three digits after a single comma reads as thousands in these exports
            String[] parts = cleaned.split(",");
            boolean thousands = parts.length > 2 || (parts.length == 2 && parts[1].length() == 3 && parts[0].length() <= 3);
            normalized = thousands ? cleaned.replace(",", "") : cleaned.replace(',', '.');
        } else if (lastDot >= 0) {
            String[] parts = cleaned.split("\\.");
            boolean thousands = parts.length > 2 || (parts.length == 2 && parts[1].length() == 3 && parts[0].length() <= 3);
            normalized = thousands ? cleaned.replace(".", "") : cleaned;
        } else {
            normalized = cleaned;
        }
        try {
            return Optional.of(new BigDecimal(normalized));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<Integer> integer(String raw) {
        return decimal(raw).map(value -> value.setScale(0, RoundingMode.HALF_UP).intValueExact());
    }

    /** "18", "18 %", "18,00", "0,18" → 18.00 ; values at most 1 are read as fractions. */
    public static Optional<BigDecimal> percent(String raw) {
        return decimal(raw).map(value -> value.compareTo(BigDecimal.ONE) <= 0 && value.signum() >= 0 && value.scale() > 0
                ? value.movePointRight(2).setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP));
    }

    public static Optional<LocalDate> date(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.contains("T") || value.contains(" ")) {
            value = value.split("[T ]")[0];
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(value, format));
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        for (DateTimeFormatter format : MONTH_FORMATS) {
            try {
                return Optional.of(YearMonth.parse(value, format).atEndOfMonth());
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        if (value.matches("\\d{4,6}(\\.\\d+)?")) {
            long serial = (long) Double.parseDouble(value);
            if (serial > 20_000 && serial < 80_000) {
                return Optional.of(EXCEL_EPOCH.plusDays(serial));
            }
        }
        return Optional.empty();
    }

    public static boolean truthy(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase()) {
            case "1", "oui", "o", "yes", "y", "vrai", "true", "x", "actif" -> true;
            default -> false;
        };
    }
}
