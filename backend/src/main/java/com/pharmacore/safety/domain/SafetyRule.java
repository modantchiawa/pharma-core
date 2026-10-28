package com.pharmacore.safety.domain;

import java.util.Objects;

/**
 * One line of the referential. {@code INTERACTION}: two substance tokens (equal tokens mean
 * "two products of this class"); {@code CONTEXT}: a substance token and a patient situation.
 */
public record SafetyRule(Kind kind, String substance, String counterpart, Severity severity, String advice) {

    public enum Kind {
        INTERACTION,
        CONTEXT
    }

    public SafetyRule {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        substance = SubstanceNames.normalize(substance);
        counterpart = kind == Kind.CONTEXT ? counterpart.trim().toUpperCase(java.util.Locale.ROOT)
                : SubstanceNames.normalize(counterpart);
        advice = advice == null ? "" : advice.trim();
        if (substance.isEmpty() || counterpart.isEmpty()) {
            throw new IllegalArgumentException("A rule names a substance and a counterpart");
        }
        if (kind == Kind.INTERACTION && substance.compareTo(counterpart) > 0) {
            String first = counterpart;
            counterpart = substance;
            substance = first;
        }
    }

    public boolean classDuplicate() {
        return kind == Kind.INTERACTION && substance.equals(counterpart);
    }

    public HealthContext context() {
        return kind == Kind.CONTEXT ? HealthContext.valueOf(counterpart) : null;
    }
}
