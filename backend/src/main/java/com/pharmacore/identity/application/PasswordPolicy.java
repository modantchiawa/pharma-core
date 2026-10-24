package com.pharmacore.identity.application;

import com.pharmacore.shared.web.BusinessRuleException;
import java.security.SecureRandom;

/** Password and PIN rules, kept simple enough for counter staff yet resistant to guessing. */
public final class PasswordPolicy {

    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int PIN_LENGTH_MIN = 4;
    public static final int PIN_LENGTH_MAX = 6;
    private static final String TEMP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessRuleException("weak-password",
                    "Le mot de passe doit contenir au moins " + MIN_PASSWORD_LENGTH + " caractères");
        }
        boolean letters = password.chars().anyMatch(Character::isLetter);
        boolean digits = password.chars().anyMatch(Character::isDigit);
        if (!letters || !digits) {
            throw new BusinessRuleException("weak-password", "Le mot de passe doit mêler lettres et chiffres");
        }
    }

    public static void validatePin(String pin) {
        if (pin == null || !pin.matches("^[0-9]{" + PIN_LENGTH_MIN + "," + PIN_LENGTH_MAX + "}$")) {
            throw new BusinessRuleException("invalid-pin", "Le code PIN comporte 4 à 6 chiffres");
        }
        boolean allSame = pin.chars().distinct().count() == 1;
        boolean sequence = "0123456789".contains(pin) || "9876543210".contains(pin);
        if (allSame || sequence) {
            throw new BusinessRuleException("invalid-pin", "Le code PIN est trop prévisible");
        }
    }

    public static String temporaryPassword() {
        var builder = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            builder.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        return builder.toString();
    }
}
