package com.pharmacore.catalog.domain;

import java.util.Objects;

/**
 * French CIP13 drug code (EAN-13 compatible, usually prefixed {@code 34009}). The check digit
 * is validated so that a mistyped manual entry is rejected before reaching the stock.
 */
public record Cip13(String value) {

    public Cip13 {
        Objects.requireNonNull(value, "cip13");
        if (!value.matches("^[0-9]{13}$")) {
            throw new IllegalArgumentException("CIP13 must contain exactly 13 digits");
        }
        if (checkDigit(value.substring(0, 12)) != value.charAt(12) - '0') {
            throw new IllegalArgumentException("Invalid CIP13 check digit: " + value);
        }
    }

    public static boolean isValid(String value) {
        try {
            new Cip13(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /** EAN-13 check digit of the first 12 digits (weights 1,3,1,3... from the left). */
    public static int checkDigit(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = first12.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10;
    }

    @Override
    public String toString() {
        return value;
    }
}
