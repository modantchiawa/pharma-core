package com.pharmacore.importing.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which column of the file feeds which field of the import. Built automatically from the
 * headers (exact alias first, then an alias contained in the header), then adjusted by the user.
 */
public record ColumnMapping(Map<String, Integer> columnByField) {

    public ColumnMapping {
        columnByField = Map.copyOf(columnByField);
    }

    public static ColumnMapping detect(ImportKind kind, List<String> headers) {
        List<String> normalized = headers.stream().map(ColumnMapping::normalize).toList();
        Map<String, Integer> mapping = new LinkedHashMap<>();
        List<Integer> taken = new ArrayList<>();
        // pass 1: exact alias match, in field order (so "prix public" wins over a looser "prix")
        for (ImportKind.Field field : kind.fields()) {
            for (String alias : field.aliases()) {
                int column = normalized.indexOf(normalize(alias));
                if (column >= 0 && !taken.contains(column)) {
                    mapping.put(field.name(), column);
                    taken.add(column);
                    break;
                }
            }
        }
        // pass 2: alias contained in the header ("Prix de vente TTC (FCFA)")
        for (ImportKind.Field field : kind.fields()) {
            if (mapping.containsKey(field.name())) {
                continue;
            }
            outer:
            for (String alias : field.aliases()) {
                String needle = normalize(alias);
                if (needle.length() < 3) {
                    continue;
                }
                for (int column = 0; column < normalized.size(); column++) {
                    if (!taken.contains(column) && normalized.get(column).contains(needle)) {
                        mapping.put(field.name(), column);
                        taken.add(column);
                        break outer;
                    }
                }
            }
        }
        return new ColumnMapping(mapping);
    }

    public Optional<Integer> column(String field) {
        return Optional.ofNullable(columnByField.get(field));
    }

    /** Required fields without a column, by label, for the user to fix before running. */
    public List<String> missingRequired(ImportKind kind) {
        return kind.fields().stream()
                .filter(ImportKind.Field::required)
                .filter(field -> !columnByField.containsKey(field.name()))
                .map(ImportKind.Field::label)
                .toList();
    }

    /** Value of a field on a row, "" when unmapped or absent. */
    public String value(TabularFile file, int row, String field) {
        Integer column = columnByField.get(field);
        return column == null ? "" : file.cell(row, column);
    }

    /** Lower case, no accents, single spaces, no punctuation: "N° de Lot" and "numero de lot" meet. */
    public static String normalize(String header) {
        String stripped = Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replace('°', ' ')
                .replaceAll("[_\\-./()\\[\\]:;,'\"]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return stripped;
    }
}
