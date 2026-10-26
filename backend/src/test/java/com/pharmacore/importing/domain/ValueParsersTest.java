package com.pharmacore.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ValueParsersTest {

    @Test
    void readsAmountsAsWrittenInAfricanAndEuropeanExports() {
        // XOF has no decimals: minor units are whole francs
        assertThat(ValueParsers.cents("1 250", 0)).contains(1_250L);
        assertThat(ValueParsers.cents("1250 FCFA", 0)).contains(1_250L);
        assertThat(ValueParsers.cents("1.250", 0)).contains(1_250L);
        assertThat(ValueParsers.cents("1,250", 0)).contains(1_250L);
        assertThat(ValueParsers.cents("1250,00", 0)).contains(1_250L);
        assertThat(ValueParsers.cents("12,5", 0)).contains(13L);
        assertThat(ValueParsers.cents("-300", 0)).contains(-300L);
        // EUR: cents
        assertThat(ValueParsers.cents("1.250,50", 2)).contains(125_050L);
        assertThat(ValueParsers.cents("12,5", 2)).contains(1_250L);
        assertThat(ValueParsers.cents("abc", 0)).isEmpty();
        assertThat(ValueParsers.cents("", 0)).isEmpty();
    }

    @Test
    void readsPercentagesAndIntegers() {
        assertThat(ValueParsers.percent("18")).contains(new BigDecimal("18.00"));
        assertThat(ValueParsers.percent("18 %")).contains(new BigDecimal("18.00"));
        assertThat(ValueParsers.percent("0,18")).contains(new BigDecimal("18.00"));
        assertThat(ValueParsers.percent("2,1")).contains(new BigDecimal("2.10"));
        assertThat(ValueParsers.percent("0")).contains(new BigDecimal("0.00"));
        assertThat(ValueParsers.integer("48")).contains(48);
        assertThat(ValueParsers.integer("1 048")).contains(1048);
        assertThat(ValueParsers.integer("12,0")).contains(12);
    }

    @Test
    void readsDatesInFrenchIsoMonthAndExcelForms() {
        assertThat(ValueParsers.date("31/12/2027")).contains(LocalDate.of(2027, 12, 31));
        assertThat(ValueParsers.date("2027-12-31")).contains(LocalDate.of(2027, 12, 31));
        assertThat(ValueParsers.date("2027-12-31T00:00:00")).contains(LocalDate.of(2027, 12, 31));
        assertThat(ValueParsers.date("09/2027")).contains(LocalDate.of(2027, 9, 30));
        assertThat(ValueParsers.date("202709")).contains(LocalDate.of(2027, 9, 30));
        assertThat(ValueParsers.date("46660")).contains(LocalDate.of(2027, 9, 30));
        assertThat(ValueParsers.date("n/a")).isEmpty();
    }

    @Test
    void normalizesProductCodes() {
        assertThat(ValueParsers.gtin13("3400930000000")).contains("3400930000000");
        assertThat(ValueParsers.gtin13("3 400 930 000 000")).contains("3400930000000");
        assertThat(ValueParsers.gtin13("930000000")).contains("0000930000000");
        assertThat(ValueParsers.gtin13("PARA-500")).isEmpty();
        assertThat(ValueParsers.gtin13("500")).isEmpty();
        assertThat(ValueParsers.gtin13("12345670")).contains("0000012345670");
        assertThat(ValueParsers.gtin13("12345678901234")).isEmpty();
        assertThat(ValueParsers.truthy("Oui")).isTrue();
        assertThat(ValueParsers.truthy("non")).isFalse();
    }
}
