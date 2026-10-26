package com.pharmacore.importing.domain;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Minimal reader of Excel workbooks (Office Open XML): first worksheet, shared and inline
 * strings, numbers and formulas' cached values. Dates stay as Excel serial numbers (the parsers
 * convert them when the target field is a date). No external dependency: a .xlsx is a zip of XML.
 */
public final class XlsxReader {

    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4B, 0x03, 0x04};

    private XlsxReader() {
    }

    public static boolean isXlsx(byte[] content) {
        if (content.length < 4) {
            return false;
        }
        for (int i = 0; i < ZIP_SIGNATURE.length; i++) {
            if (content[i] != ZIP_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    public static TabularFile read(byte[] content) {
        Map<String, byte[]> parts = unzip(content);
        List<String> sharedStrings = parseSharedStrings(parts.get("xl/sharedStrings.xml"));
        String sheetPath = firstSheetPath(parts);
        byte[] sheet = parts.get(sheetPath);
        if (sheet == null) {
            throw new IllegalArgumentException("Classeur Excel sans feuille de calcul");
        }
        List<List<String>> records = parseSheet(sheet, sharedStrings);
        while (!records.isEmpty() && records.getFirst().stream().allMatch(String::isBlank)) {
            records.removeFirst();
        }
        if (records.isEmpty()) {
            return new TabularFile("XLSX", "UTF-8", "", List.of(), List.of());
        }
        List<String> headers = records.getFirst().stream().map(String::trim).toList();
        List<List<String>> rows = new ArrayList<>();
        for (List<String> record : records.subList(1, records.size())) {
            if (record.stream().allMatch(String::isBlank)) {
                continue;
            }
            rows.add(record.stream().map(String::trim).toList());
        }
        return new TabularFile("XLSX", "UTF-8", "", headers, rows);
    }

    private static Map<String, byte[]> unzip(byte[] content) {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith("xl/")) {
                    parts.put(entry.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Fichier Excel illisible", e);
        }
        return parts;
    }

    /** The workbook lists sheets in order; the first one is the data sheet of an export. */
    private static String firstSheetPath(Map<String, byte[]> parts) {
        byte[] workbook = parts.get("xl/workbook.xml");
        byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
        if (workbook != null && rels != null) {
            String firstSheetRel = null;
            try {
                XMLStreamReader reader = reader(workbook);
                while (reader.hasNext() && firstSheetRel == null) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(reader.getLocalName())) {
                        firstSheetRel = attribute(reader, "id");
                    }
                }
                if (firstSheetRel != null) {
                    XMLStreamReader relReader = reader(rels);
                    while (relReader.hasNext()) {
                        if (relReader.next() == XMLStreamConstants.START_ELEMENT
                                && "Relationship".equals(relReader.getLocalName())
                                && firstSheetRel.equals(relReader.getAttributeValue(null, "Id"))) {
                            String target = relReader.getAttributeValue(null, "Target");
                            return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                        }
                    }
                }
            } catch (XMLStreamException e) {
                throw new IllegalArgumentException("Classeur Excel illisible", e);
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> parseSharedStrings(byte[] part) {
        List<String> strings = new ArrayList<>();
        if (part == null) {
            return strings;
        }
        try {
            XMLStreamReader reader = reader(part);
            StringBuilder current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && "si".equals(reader.getLocalName())) {
                    current = new StringBuilder();
                } else if (event == XMLStreamConstants.START_ELEMENT && "t".equals(reader.getLocalName()) && current != null) {
                    current.append(reader.getElementText());
                } else if (event == XMLStreamConstants.END_ELEMENT && "si".equals(reader.getLocalName()) && current != null) {
                    strings.add(current.toString());
                    current = null;
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Chaînes partagées Excel illisibles", e);
        }
        return strings;
    }

    private static List<List<String>> parseSheet(byte[] sheet, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader reader = reader(sheet);
            List<String> row = null;
            String cellType = null;
            int column = -1;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (reader.getLocalName()) {
                        case "row" -> row = new ArrayList<>();
                        case "c" -> {
                            cellType = reader.getAttributeValue(null, "t");
                            column = columnIndex(reader.getAttributeValue(null, "r"));
                        }
                        case "v", "t" -> {
                            if (row != null && column >= 0 && ("v".equals(reader.getLocalName())
                                    || "inlineStr".equals(cellType))) {
                                String raw = reader.getElementText();
                                String value = switch (cellType == null ? "n" : cellType) {
                                    case "s" -> sharedStrings.get(Integer.parseInt(raw));
                                    case "b" -> "1".equals(raw) ? "VRAI" : "FAUX";
                                    case "n" -> number(raw);
                                    default -> raw;
                                };
                                while (row.size() <= column) {
                                    row.add("");
                                }
                                row.set(column, value);
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("row".equals(reader.getLocalName()) && row != null) {
                        rows.add(row);
                        row = null;
                    } else if ("c".equals(reader.getLocalName())) {
                        cellType = null;
                        column = -1;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Feuille Excel illisible", e);
        }
        return rows;
    }

    /** Renders Excel numbers without scientific notation or a trailing ".0". */
    private static String number(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** "C12" → 2 ; a cell without reference falls back to the next column. */
    private static int columnIndex(String reference) {
        if (reference == null) {
            return -1;
        }
        int index = 0;
        for (char c : reference.toCharArray()) {
            if (Character.isLetter(c)) {
                index = index * 26 + (Character.toUpperCase(c) - 'A' + 1);
            } else {
                break;
            }
        }
        return index - 1;
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (localName.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    private static XMLStreamReader reader(byte[] part) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        InputStream in = new ByteArrayInputStream(part);
        return factory.createXMLStreamReader(in);
    }
}
