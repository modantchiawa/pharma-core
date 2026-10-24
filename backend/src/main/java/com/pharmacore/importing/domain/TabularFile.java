package com.pharmacore.importing.domain;

import java.util.List;

/**
 * A spreadsheet or delimited file reduced to its cells: a header row and data rows of strings.
 * Everything downstream (mapping, parsing, loading) works on this shape whatever the source.
 *
 * @param format    CSV or XLSX
 * @param charset   detected charset for CSV, "UTF-8" for XLSX
 * @param delimiter detected delimiter for CSV, "" for XLSX
 */
public record TabularFile(String format, String charset, String delimiter, List<String> headers,
                          List<List<String>> rows) {

    public TabularFile {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }

    /** Cell at (row, column) or "" when the row is shorter than the header. */
    public String cell(int row, int column) {
        List<String> values = rows.get(row);
        return column < values.size() ? values.get(column) : "";
    }

    public int size() {
        return rows.size();
    }
}
