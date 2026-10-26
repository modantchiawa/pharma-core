package com.pharmacore.importing.domain;

/** Picks the reader from the content itself (an .xlsx is a zip), never from the file extension alone. */
public final class TabularReaders {

    private TabularReaders() {
    }

    public static TabularFile read(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Fichier vide");
        }
        if (XlsxReader.isXlsx(content)) {
            return XlsxReader.read(content);
        }
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".xls")) {
            throw new IllegalArgumentException("Format .xls (Excel 97-2003) non pris en charge : enregistrez le fichier en .xlsx ou en CSV");
        }
        return CsvReader.read(content);
    }
}
