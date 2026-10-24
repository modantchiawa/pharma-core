package com.pharmacore.importing.domain;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one import run: counters plus the first rejected rows with the reason, 1-based line numbers. */
public final class ImportReport {

    public static final int MAX_ERRORS_KEPT = 200;

    public record RowError(int line, String message) {
    }

    private int total;
    private int created;
    private int updated;
    private int rejected;
    private final List<RowError> errors = new ArrayList<>();

    public void created() {
        total++;
        created++;
    }

    public void updated() {
        total++;
        updated++;
    }

    public void rejected(int line, String message) {
        total++;
        rejected++;
        if (errors.size() < MAX_ERRORS_KEPT) {
            errors.add(new RowError(line, message));
        }
    }

    public int total() {
        return total;
    }

    public int createdCount() {
        return created;
    }

    public int updatedCount() {
        return updated;
    }

    public int rejectedCount() {
        return rejected;
    }

    public List<RowError> errors() {
        return List.copyOf(errors);
    }
}
