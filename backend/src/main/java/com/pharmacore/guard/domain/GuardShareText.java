package com.pharmacore.guard.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Plain-text duty roster ready to be pasted in WhatsApp, an SMS or a Facebook post: one line per
 * pharmacy on duty with its phone number. Pure domain logic, no framework.
 */
public final class GuardShareText {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH'h'mm", Locale.FRENCH);
    private static final int MAX_LINES = 60;

    private GuardShareText() {
    }

    /**
     * @param scope  what the roster covers: a city ("Niamey") or a network name
     * @param date   the day announced, in the pharmacies' time zone
     * @param slots  slots overlapping that day; duplicates per pharmacy are merged in reading order
     */
    public static String render(String scope, LocalDate date, ZoneId zone, List<Entry> slots) {
        var text = new StringBuilder();
        text.append("Pharmacies de garde");
        if (scope != null && !scope.isBlank()) {
            text.append(" à ").append(scope.trim());
        }
        text.append(" — ").append(DAY.format(date)).append('\n');
        if (slots.isEmpty()) {
            text.append("Aucune garde publiée pour cette date.\n");
            return text.toString();
        }
        List<Entry> ordered = slots.stream()
                .sorted(Comparator.comparing(Entry::startsAt).thenComparing(Entry::pharmacyName))
                .limit(MAX_LINES)
                .toList();
        for (Entry entry : ordered) {
            text.append("- ").append(entry.pharmacyName());
            if (entry.address() != null && !entry.address().isBlank()) {
                text.append(", ").append(entry.address().trim());
            }
            if (entry.phone() != null && !entry.phone().isBlank()) {
                text.append(", tél. ").append(entry.phone().trim());
            }
            text.append(" (").append(label(entry.type())).append(' ')
                    .append(HOUR.format(entry.startsAt().atZone(zone)))
                    .append('-')
                    .append(HOUR.format(entry.endsAt().atZone(zone)))
                    .append(")\n");
        }
        if (slots.size() > MAX_LINES) {
            text.append("… et ").append(slots.size() - MAX_LINES).append(" autre(s)\n");
        }
        text.append("Source : PharmaCore");
        return text.toString();
    }

    static String label(String type) {
        return switch (type) {
            case "NIGHT" -> "nuit";
            case "SUNDAY" -> "dimanche";
            case "PUBLIC_HOLIDAY" -> "jour férié";
            case "EXTENDED_DAY" -> "journée étendue";
            default -> type.toLowerCase(Locale.ROOT);
        };
    }

    public record Entry(String pharmacyName, String address, String phone, String type, Instant startsAt,
                        Instant endsAt) {
    }
}
