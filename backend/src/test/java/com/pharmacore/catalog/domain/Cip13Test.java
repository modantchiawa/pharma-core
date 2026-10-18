package com.pharmacore.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Cip13Test {

    @Test
    void acceptsCodeWithValidCheckDigit() {
        String first12 = "340093000001";
        String code = first12 + Cip13.checkDigit(first12);

        assertThat(new Cip13(code).value()).isEqualTo(code);
        assertThat(Cip13.isValid(code)).isTrue();
    }

    @Test
    void rejectsWrongCheckDigit() {
        String first12 = "340093000001";
        int wrong = (Cip13.checkDigit(first12) + 1) % 10;

        assertThatThrownBy(() -> new Cip13(first12 + wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("check digit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "34009300000ab", "34009300000123"})
    void rejectsMalformedCodes(String code) {
        assertThat(Cip13.isValid(code)).isFalse();
    }

    @Test
    void computesStandardEan13CheckDigit() {
        // 4006381333931 is a reference EAN-13 example
        assertThat(Cip13.checkDigit("400638133393")).isEqualTo(1);
    }
}
