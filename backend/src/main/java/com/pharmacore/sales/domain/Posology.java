package com.pharmacore.sales.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * How the patient takes the product, as the pharmacist typed it at the counter: units at each
 * moment of the day, for how many days, with or without food, plus a free instruction. Pure
 * value: the summary printed on the ticket and the end of the treatment derive from it.
 */
public record Posology(int morning, int noon, int evening, int bedtime, int durationDays, boolean withFood,
                       String instructions) {

    public static final int MAX_UNITS_PER_TAKE = 20;
    public static final int MAX_DURATION_DAYS = 365;
    public static final int MAX_INSTRUCTIONS = 120;

    public Posology {
        for (int units : new int[] {morning, noon, evening, bedtime}) {
            if (units < 0 || units > MAX_UNITS_PER_TAKE) {
                throw new IllegalArgumentException("Units per take must be between 0 and " + MAX_UNITS_PER_TAKE);
            }
        }
        if (durationDays < 0 || durationDays > MAX_DURATION_DAYS) {
            throw new IllegalArgumentException("Duration must be between 0 and " + MAX_DURATION_DAYS + " days");
        }
        instructions = instructions == null || instructions.isBlank() ? null : instructions.trim();
        if (instructions != null && instructions.length() > MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException("Instructions are limited to " + MAX_INSTRUCTIONS + " characters");
        }
        if (morning + noon + evening + bedtime == 0 && durationDays == 0 && instructions == null) {
            throw new IllegalArgumentException("An empty posology is not a posology");
        }
    }

    public int dailyUnits() {
        return morning + noon + evening + bedtime;
    }

    /** Last day of the treatment when it starts on {@code start}; null without a duration. */
    public LocalDate endsOn(LocalDate start) {
        return durationDays == 0 ? null : start.plusDays(durationDays - 1L);
    }

    /** "1 matin · 1 midi · 1 soir · 7 jours · pendant les repas" */
    public String summary() {
        List<String> parts = new ArrayList<>();
        if (morning > 0) {
            parts.add(morning + " matin");
        }
        if (noon > 0) {
            parts.add(noon + " midi");
        }
        if (evening > 0) {
            parts.add(evening + " soir");
        }
        if (bedtime > 0) {
            parts.add(bedtime + " coucher");
        }
        if (durationDays > 0) {
            parts.add(durationDays + (durationDays > 1 ? " jours" : " jour"));
        }
        if (withFood && dailyUnits() > 0) {
            parts.add("pendant les repas");
        }
        if (instructions != null) {
            parts.add(instructions);
        }
        return String.join(" · ", parts);
    }
}
