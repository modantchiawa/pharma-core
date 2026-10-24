package com.pharmacore.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

public class TabularReadersTest {

    @Test
    void readsSemicolonCsvWithBomQuotesAndEmbeddedLineBreaks() {
        String csv = "﻿Code CIP;Désignation;Prix de vente\n"
                + "3400930000000;\"PARACETAMOL 500MG; B/16\";\"1 250\"\n"
                + "\n"
                + "3400930000017;\"Sirop \"\"toux\"\"\nenfant\";850\r\n";
        TabularFile file = TabularReaders.read(csv.getBytes(StandardCharsets.UTF_8), "produits.csv");

        assertThat(file.format()).isEqualTo("CSV");
        assertThat(file.charset()).isEqualTo("UTF-8");
        assertThat(file.delimiter()).isEqualTo(";");
        assertThat(file.headers()).containsExactly("Code CIP", "Désignation", "Prix de vente");
        assertThat(file.size()).isEqualTo(2);
        assertThat(file.cell(0, 1)).isEqualTo("PARACETAMOL 500MG; B/16");
        assertThat(file.cell(1, 1)).isEqualTo("Sirop \"toux\"\nenfant");
        assertThat(file.cell(1, 2)).isEqualTo("850");
    }

    @Test
    void detectsWindows1252AndCommaDelimiter() {
        byte[] csv = "Nom,Téléphone,Solde dû\nMme Hadiza,+22790000000,\"12,500\"\n".getBytes(Charset.forName("windows-1252"));
        TabularFile file = TabularReaders.read(csv, "clients.csv");

        assertThat(file.charset()).isEqualTo("windows-1252");
        assertThat(file.delimiter()).isEqualTo(",");
        assertThat(file.headers()).containsExactly("Nom", "Téléphone", "Solde dû");
        assertThat(file.cell(0, 2)).isEqualTo("12,500");
    }

    @Test
    void readsTabSeparatedFileAndPadsShortRows() {
        TabularFile file = TabularReaders.read("a\tb\tc\n1\t2\n".getBytes(StandardCharsets.UTF_8), "x.txt");
        assertThat(file.delimiter()).isEqualTo("\t");
        assertThat(file.cell(0, 2)).isEmpty();
    }

    @Test
    void readsFirstSheetOfAnXlsxWorkbook() throws IOException {
        byte[] workbook = xlsx("""
                <sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="inlineStr"><is><t>Péremption</t></is></c></row>
                  <row r="2"><c r="A2"><v>3400930000000</v></c><c r="B2" t="s"><v>2</v></c><c r="C2"><v>46660</v></c></row>
                  <row r="3"><c r="A3"><v>1250.0</v></c><c r="B3" t="b"><v>1</v></c></row>
                </sheetData>""", "Code", "Désignation", "DOLIPRANE 1000");
        TabularFile file = TabularReaders.read(workbook, "stock.xlsx");

        assertThat(file.format()).isEqualTo("XLSX");
        assertThat(file.headers()).containsExactly("Code", "Désignation", "Péremption");
        assertThat(file.size()).isEqualTo(2);
        assertThat(file.cell(0, 0)).isEqualTo("3400930000000");
        assertThat(file.cell(0, 1)).isEqualTo("DOLIPRANE 1000");
        assertThat(file.cell(0, 2)).isEqualTo("46660");
        assertThat(file.cell(1, 0)).isEqualTo("1250");
        assertThat(file.cell(1, 1)).isEqualTo("VRAI");
    }

    @Test
    void refusesLegacyXlsAndEmptyFiles() {
        assertThatThrownBy(() -> TabularReaders.read(new byte[] {1, 2, 3}, "ancien.xls"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".xlsx");
        assertThatThrownBy(() -> TabularReaders.read(new byte[0], "vide.csv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    public static byte[] xlsx(String sheetData, String... sharedStrings) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            entry(zip, "[Content_Types].xml", "<Types/>");
            entry(zip, "xl/workbook.xml", """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Stock" sheetId="1" r:id="rId7"/><sheet name="Notes" sheetId="2" r:id="rId8"/></sheets>
                    </workbook>""");
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId8" Type="x" Target="worksheets/sheet2.xml"/>
                      <Relationship Id="rId7" Type="x" Target="worksheets/sheet9.xml"/>
                    </Relationships>""");
            var strings = new StringBuilder("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            for (String s : sharedStrings) {
                strings.append("<si><t>").append(s).append("</t></si>");
            }
            entry(zip, "xl/sharedStrings.xml", strings.append("</sst>").toString());
            entry(zip, "xl/worksheets/sheet9.xml",
                    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" + sheetData + "</worksheet>");
            entry(zip, "xl/worksheets/sheet2.xml",
                    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>");
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
