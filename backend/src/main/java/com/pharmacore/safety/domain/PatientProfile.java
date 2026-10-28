package com.pharmacore.safety.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the pharmacy recorded about the patient. Allergies are family codes
 * ({@link AllergyFamily}) or free-text substance names.
 */
public record PatientProfile(LocalDate birthDate, boolean pregnant, boolean breastfeeding, List<String> allergies,
                             Set<HealthContext> conditions) {

    public static final int CHILD_BELOW_YEARS = 12;
    public static final int ELDERLY_FROM_YEARS = 65;

    public static final PatientProfile EMPTY = new PatientProfile(null, false, false, List.of(), Set.of());

    public PatientProfile {
        allergies = allergies == null ? List.of() : List.copyOf(allergies);
        conditions = conditions == null || conditions.isEmpty() ? Set.of() : EnumSet.copyOf(conditions);
    }

    public Integer ageYears(LocalDate today) {
        return birthDate == null || birthDate.isAfter(today) ? null : Period.between(birthDate, today).getYears();
    }

    /** Situations in force today: declared conditions plus those derived from age, pregnancy and breastfeeding. */
    public Set<HealthContext> contexts(LocalDate today) {
        Set<HealthContext> contexts = EnumSet.noneOf(HealthContext.class);
        contexts.addAll(conditions);
        if (pregnant) {
            contexts.add(HealthContext.PREGNANCY);
        }
        if (breastfeeding) {
            contexts.add(HealthContext.BREASTFEEDING);
        }
        Integer age = ageYears(today);
        if (age != null && age < CHILD_BELOW_YEARS) {
            contexts.add(HealthContext.CHILD);
        }
        if (age != null && age >= ELDERLY_FROM_YEARS) {
            contexts.add(HealthContext.ELDERLY);
        }
        return contexts;
    }

    public boolean isEmpty() {
        return birthDate == null && !pregnant && !breastfeeding && allergies.isEmpty() && conditions.isEmpty();
    }
}
