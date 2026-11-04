package com.pharmacore.importing.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal writer of Excel workbooks (Office Open XML), the counterpart of {@link XlsxReader}:
 * one or more worksheets of text, numbers, amounts and dates, a bold header row and frozen
 * header. Strings are written inline (no shared-string table), so a workbook is a handful of
 * small XML parts in a zip. No external dependency.
 */
public final class XlsxWriter {

    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    /** Style indexes of {@link #STYLES}: 0 plain, 1 bold header, 2 integer, 3 amount, 4 date, 5 bold amount. */
    private static final int HEADER = 1;
    private static final int INTEGER = 2;
    private static final int AMOUNT = 3;
    private static final int DATE = 4;
    private static final int TOTAL = 5;

    private XlsxWriter() {
    }

    /** One cell. Build with {@link #text}, {@link #number}, {@link #amount}, {@link #date} or {@link #empty}. */
    public sealed interface Cell permits Text, Number, Amount, DateCell, Empty {
    }

    public record Text(String value, boolean bold) implements Cell {
    }

    public record Number(BigDecimal value) implements Cell {
    }

    /** An amount in major units (francs), rendered with thousands separators. */
    public record Amount(BigDecimal value, boolean bold) implements Cell {
    }

    public record DateCell(LocalDate value) implements Cell {
    }

    public record Empty() implements Cell {
    }

    public static Cell text(String value) {
        return value == null ? new Empty() : new Text(value, false);
    }

    public static Cell bold(String value) {
        return value == null ? new Empty() : new Text(value, true);
    }

    public static Cell number(long value) {
        return new Number(BigDecimal.valueOf(value));
    }

    public static Cell number(BigDecimal value) {
        return value == null ? new Empty() : new Number(value);
    }

    /**
     * @param minorUnits amount in minor units of the currency
     * @param scale      decimals of the currency (0 for the XOF)
     */
    public static Cell amount(Long minorUnits, int scale) {
        return minorUnits == null ? new Empty() : new Amount(BigDecimal.valueOf(minorUnits, scale), false);
    }

    public static Cell totalAmount(long minorUnits, int scale) {
        return new Amount(BigDecimal.valueOf(minorUnits, scale), true);
    }

    public static Cell date(LocalDate value) {
        return value == null ? new Empty() : new DateCell(value);
    }

    public static Cell empty() {
        return new Empty();
    }

    /**
     * A worksheet: optional title lines above the table, the column headers, then the rows.
     *
     * @param widths column widths in characters (shorter than {@code headers}: the rest get 14)
     */
    public record Sheet(String name, List<List<Cell>> preamble, List<String> headers, List<List<Cell>> rows,
                        List<Integer> widths) {

        public Sheet {
            Objects.requireNonNull(name, "name");
            preamble = preamble == null ? List.of() : List.copyOf(preamble);
            headers = List.copyOf(headers);
            rows = List.copyOf(rows);
            widths = widths == null ? List.of() : List.copyOf(widths);
        }

        public Sheet(String name, List<String> headers, List<List<Cell>> rows) {
            this(name, List.of(), headers, rows, List.of());
        }
    }

    public static byte[] write(Sheet... sheets) {
        return write(List.of(sheets));
    }

    public static byte[] write(List<Sheet> sheets) {
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("A workbook needs at least one sheet");
        }
        List<String> names = uniqueNames(sheets);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    </Relationships>""");
            put(zip, "xl/workbook.xml", workbook(names));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            put(zip, "xl/styles.xml", STYLES);
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------ parts

    private static String sheet(Sheet sheet) {
        var xml = new StringBuilder(4096);
        int headerRow = sheet.preamble().size() + (sheet.preamble().isEmpty() ? 1 : 2);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"").append(headerRow)
                .append("\" topLeftCell=\"A").append(headerRow + 1)
                .append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        int columns = Math.max(sheet.headers().size(), 1);
        xml.append("<cols>");
        for (int c = 0; c < columns; c++) {
            int width = c < sheet.widths().size() ? sheet.widths().get(c) : 14;
            xml.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                    .append(width).append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData>");
        int rowNo = 1;
        for (List<Cell> line : sheet.preamble()) {
            row(xml, rowNo++, line);
        }
        if (!sheet.preamble().isEmpty()) {
            rowNo++;
        }
        List<Cell> header = new ArrayList<>();
        sheet.headers().forEach(h -> header.add(new Text(h, true)));
        row(xml, rowNo++, header, HEADER);
        for (List<Cell> line : sheet.rows()) {
            row(xml, rowNo++, line);
        }
        xml.append("</sheetData></worksheet>");
        return xml.toString();
    }

    private static void row(StringBuilder xml, int rowNo, List<Cell> cells) {
        row(xml, rowNo, cells, -1);
    }

    private static void row(StringBuilder xml, int rowNo, List<Cell> cells, int forcedStyle) {
        xml.append("<row r=\"").append(rowNo).append("\">");
        for (int c = 0; c < cells.size(); c++) {
            String ref = column(c) + rowNo;
            switch (cells.get(c)) {
                case Text t -> xml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"")
                        .append(style(forcedStyle >= 0 ? forcedStyle : t.bold() ? HEADER : 0))
                        .append("><is><t xml:space=\"preserve\">").append(escape(t.value())).append("</t></is></c>");
                case Number n -> xml.append("<c r=\"").append(ref).append("\"").append(style(INTEGER)).append("><v>")
                        .append(n.value().toPlainString()).append("</v></c>");
                case Amount a -> xml.append("<c r=\"").append(ref).append("\"").append(style(a.bold() ? TOTAL : AMOUNT))
                        .append("><v>").append(a.value().toPlainString()).append("</v></c>");
                case DateCell d -> xml.append("<c r=\"").append(ref).append("\"").append(style(DATE)).append("><v>")
                        .append(ChronoUnit.DAYS.between(EXCEL_EPOCH, d.value())).append("</v></c>");
                case Empty e -> {
                }
            }
        }
        xml.append("</row>");
    }

    private static String style(int index) {
        return index == 0 ? "" : " s=\"" + index + "\"";
    }

    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
            <numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0"/><numFmt numFmtId="165" formatCode="dd/mm/yyyy"/></numFmts>\
            <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>\
            <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>\
            <fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF4"/></patternFill></fill></fills>\
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>\
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
            <cellXfs count="6">\
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
            <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>\
            <xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            <xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>\
            </cellXfs>\
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>\
            </styleSheet>""";

    private static String contentTypes(int sheets) {
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    private static String workbook(List<String> names) {
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""");
        for (int i = 0; i < names.size(); i++) {
            xml.append("<sheet name=\"").append(escape(names.get(i))).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int sheets) {
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        xml.append("<Relationship Id=\"rId").append(sheets + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return xml.append("</Relationships>").toString();
    }

    // ------------------------------------------------------------------ helpers

    /** Excel refuses duplicate names, names over 31 characters and the characters []:*?/\ . */
    static List<String> uniqueNames(List<Sheet> sheets) {
        List<String> names = new ArrayList<>();
        for (Sheet sheet : sheets) {
            String base = sheet.name().replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
            if (base.isEmpty()) {
                base = "Feuille";
            }
            base = base.length() > 31 ? base.substring(0, 31) : base;
            String name = base;
            for (int n = 2; names.contains(name); n++) {
                String suffix = " (" + n + ")";
                name = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
            }
            names.add(name);
        }
        return names;
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String column(int index) {
        var name = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            name.insert(0, (char) ('A' + i % 26));
        }
        return name.toString();
    }

    private static String escape(String value) {
        var out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    // control characters are not allowed in XML 1.0
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
