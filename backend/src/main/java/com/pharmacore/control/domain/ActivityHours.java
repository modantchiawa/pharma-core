package com.pharmacore.control.domain;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Opening hours of the pharmacy, one slot per weekday; a day without slot is closed. Sales and
 * stock adjustments outside these hours are flagged, except while the pharmacy is on guard duty
 * (the caller checks the guard schedule).
 */
public record ActivityHours(Map<DayOfWeek, Slot> days) {

    /** Opening and closing time of a day; {@code close} is exclusive. */
    public record Slot(LocalTime open, LocalTime close) {

        public Slot {
            if (open == null || close == null || !close.isAfter(open)) {
                throw new IllegalArgumentException("L'heure de fermeture doit suivre l'heure d'ouverture");
            }
        }
    }

    /** A weekday line as stored and sent to the settings screen. */
    public record Day(DayOfWeek day, LocalTime open, LocalTime close) {
    }

    public ActivityHours {
        Map<DayOfWeek, Slot> copy = new EnumMap<>(DayOfWeek.class);
        copy.putAll(days);
        days = Collections.unmodifiableMap(copy);
    }

    /** Every day from 07:00 to 22:00. */
    public static ActivityHours defaults() {
        Map<DayOfWeek, Slot> days = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            days.put(day, new Slot(LocalTime.of(7, 0), LocalTime.of(22, 0)));
        }
        return new ActivityHours(days);
    }

    public static ActivityHours of(List<Day> lines) {
        Map<DayOfWeek, Slot> days = new EnumMap<>(DayOfWeek.class);
        for (Day line : lines) {
            if (days.put(line.day(), new Slot(line.open(), line.close())) != null) {
                throw new IllegalArgumentException("Un seul créneau par jour : " + line.day());
            }
        }
        if (days.isEmpty()) {
            throw new IllegalArgumentException("Indiquez au moins un jour d'ouverture");
        }
        return new ActivityHours(days);
    }

    public List<Day> lines() {
        List<Day> lines = new ArrayList<>();
        days.forEach((day, slot) -> lines.add(new Day(day, slot.open(), slot.close())));
        return lines;
    }

    /** @param local wall-clock time of the pharmacy */
    public boolean isOpen(LocalDateTime local) {
        Slot slot = days.get(local.getDayOfWeek());
        LocalTime time = local.toLocalTime();
        return slot != null && !time.isBefore(slot.open()) && time.isBefore(slot.close());
    }
}
