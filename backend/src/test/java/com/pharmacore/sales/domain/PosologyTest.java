package com.pharmacore.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PosologyTest {

    @Test
    void summarisesInFrenchInTheOrderOfTheDay() {
        var posology = new Posology(1, 0, 1, 0, 7, true, "Avec un grand verre d'eau");

        assertThat(posology.summary()).isEqualTo("1 matin · 1 soir · 7 jours · pendant les repas · Avec un grand verre d'eau");
        assertThat(posology.dailyUnits()).isEqualTo(2);
        assertThat(posology.endsOn(LocalDate.of(2026, 10, 27))).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void singleDayAndNoDurationAreHandled() {
        assertThat(new Posology(2, 2, 2, 0, 1, false, null).summary()).isEqualTo("2 matin · 2 midi · 2 soir · 1 jour");
        assertThat(new Posology(0, 0, 0, 1, 0, false, " ").summary()).isEqualTo("1 coucher");
        assertThat(new Posology(0, 0, 0, 1, 0, true, null).endsOn(LocalDate.EPOCH)).isNull();
        assertThat(new Posology(0, 0, 0, 0, 0, false, "En cas de douleur").summary()).isEqualTo("En cas de douleur");
    }

    @Test
    void refusesNonsense() {
        assertThatThrownBy(() -> new Posology(0, 0, 0, 0, 0, false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Posology(-1, 0, 0, 0, 1, false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Posology(1, 0, 0, 0, 400, false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Posology(1, 0, 0, 0, 1, false, "x".repeat(121))).isInstanceOf(IllegalArgumentException.class);
    }
}
