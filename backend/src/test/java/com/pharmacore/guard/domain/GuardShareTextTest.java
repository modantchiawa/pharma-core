package com.pharmacore.guard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuardShareTextTest {

    private static final ZoneId NIAMEY = ZoneId.of("Africa/Niamey");

    @Test
    void rendersOneLinePerPharmacyInLocalTimeOrderedByStart() {
        var later = new GuardShareText.Entry("Pharmacie Zinder", "Route de Zinder", "+227 96 11 22 33", "NIGHT",
                Instant.parse("2027-01-10T20:00:00Z"), Instant.parse("2027-01-11T07:00:00Z"));
        var first = new GuardShareText.Entry("Pharmacie du Sahel", "Plateau", "+227 90 00 00 00", "NIGHT",
                Instant.parse("2027-01-10T18:00:00Z"), Instant.parse("2027-01-11T07:00:00Z"));

        String text = GuardShareText.render("Niamey", LocalDate.of(2027, 1, 10), NIAMEY, List.of(later, first));

        assertThat(text.lines().toList()).containsExactly(
                "Pharmacies de garde à Niamey — dimanche 10 janvier 2027",
                "- Pharmacie du Sahel, Plateau, tél. +227 90 00 00 00 (nuit 19h00-08h00)",
                "- Pharmacie Zinder, Route de Zinder, tél. +227 96 11 22 33 (nuit 21h00-08h00)",
                "Source : PharmaCore");
    }

    @Test
    void tellsWhenNothingIsPublishedAndSkipsMissingDetails() {
        String empty = GuardShareText.render(null, LocalDate.of(2027, 1, 10), NIAMEY, List.of());
        assertThat(empty).startsWith("Pharmacies de garde — dimanche 10 janvier 2027")
                .contains("Aucune garde publiée");

        var sparse = new GuardShareText.Entry("Pharmacie Centrale", null, null, "SUNDAY",
                Instant.parse("2027-01-10T07:00:00Z"), Instant.parse("2027-01-10T19:00:00Z"));
        String text = GuardShareText.render("  ", LocalDate.of(2027, 1, 10), NIAMEY, List.of(sparse));
        assertThat(text).contains("- Pharmacie Centrale (dimanche 08h00-20h00)")
                .doesNotContain("tél.")
                .doesNotContain(" à ");
    }

    @Test
    void capsVeryLongRosters() {
        var entries = java.util.stream.IntStream.range(0, 65)
                .mapToObj(i -> new GuardShareText.Entry("Pharmacie " + i, null, null, "NIGHT",
                        Instant.parse("2027-01-10T18:00:00Z").plusSeconds(i), Instant.parse("2027-01-11T07:00:00Z")))
                .toList();
        String text = GuardShareText.render("Niamey", LocalDate.of(2027, 1, 10), NIAMEY, entries);
        assertThat(text).contains("… et 5 autre(s)");
        assertThat(text.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(60);
    }
}
