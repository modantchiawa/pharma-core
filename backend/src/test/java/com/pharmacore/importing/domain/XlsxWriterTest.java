package com.pharmacore.importing.domain;

import static com.pharmacore.importing.domain.XlsxWriter.amount;
import static com.pharmacore.importing.domain.XlsxWriter.date;
import static com.pharmacore.importing.domain.XlsxWriter.number;
import static com.pharmacore.importing.domain.XlsxWriter.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class XlsxWriterTest {

    @Test
    void writesAWorkbookTheReaderReadsBack() {
        byte[] workbook = XlsxWriter.write(new XlsxWriter.Sheet("Commande", List.of("Code CIP", "Désignation",
                "Quantité", "Prix", "Péremption"), List.of(
                List.of(text("3400930000001"), text("DOLIPRANE 500 <cpr> & co"), number(12), amount(1_250L, 0),
                        date(LocalDate.of(2028, 1, 31))),
                List.of(text("3400930000002"), text("AMOXICILLINE"), number(3), amount(null, 0)))));

        assertThat(XlsxReader.isXlsx(workbook)).isTrue();
        TabularFile file = XlsxReader.read(workbook);
        assertThat(file.headers()).containsExactly("Code CIP", "Désignation", "Quantité", "Prix", "Péremption");
        assertThat(file.size()).isEqualTo(2);
        assertThat(file.cell(0, 1)).isEqualTo("DOLIPRANE 500 <cpr> & co");
        assertThat(file.cell(0, 2)).isEqualTo("12");
        assertThat(file.cell(0, 3)).isEqualTo("1250");
        // dates are Excel serial numbers, which the import parsers turn back into dates
        assertThat(ValueParsers.date(file.cell(0, 4))).contains(LocalDate.of(2028, 1, 31));
        assertThat(file.cell(1, 3)).isEmpty();
    }

    @Test
    void amountsKeepTheCurrencyDecimals() {
        byte[] workbook = XlsxWriter.write(new XlsxWriter.Sheet("EUR", List.of("Montant"),
                List.of(List.of(amount(1_999L, 2)))));
        assertThat(XlsxReader.read(workbook).cell(0, 0)).isEqualTo("19.99");
    }

    @Test
    void sheetNamesAreMadeValidAndUnique() {
        var sheet = new XlsxWriter.Sheet("Ventes: [jour]/nuit et bien plus que trente et un caractères", List.of("A"),
                List.of());
        List<String> names = XlsxWriter.uniqueNames(List.of(sheet, sheet));
        assertThat(names.get(0)).hasSizeLessThanOrEqualTo(31).doesNotContain(":", "[", "]", "/");
        assertThat(names.get(1)).endsWith(" (2)").hasSizeLessThanOrEqualTo(31);
    }

    @Test
    void columnsAreLettered() {
        assertThat(XlsxWriter.column(0)).isEqualTo("A");
        assertThat(XlsxWriter.column(25)).isEqualTo("Z");
        assertThat(XlsxWriter.column(26)).isEqualTo("AA");
        assertThat(XlsxWriter.column(701)).isEqualTo("ZZ");
    }
}
