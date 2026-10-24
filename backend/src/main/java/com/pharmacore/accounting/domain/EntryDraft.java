package com.pharmacore.accounting.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A double-entry record under construction; invalid (unbalanced, empty) drafts cannot be built. */
public record EntryDraft(Journal journal, LocalDate date, String label, List<Line> lines) {

    public EntryDraft {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(label, "label");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An entry needs at least one line");
        }
        long debit = lines.stream().mapToLong(Line::debitCents).sum();
        long credit = lines.stream().mapToLong(Line::creditCents).sum();
        if (debit != credit) {
            throw new IllegalArgumentException("Unbalanced entry " + label + ": debit " + debit + " vs credit " + credit);
        }
    }

    public record Line(String account, long debitCents, long creditCents, String label) {

        public Line {
            Objects.requireNonNull(account, "account");
            if (debitCents < 0 || creditCents < 0 || (debitCents == 0) == (creditCents == 0)) {
                throw new IllegalArgumentException("A line is either a debit or a credit, strictly positive");
            }
        }

        public static Line debit(String account, long cents, String label) {
            return new Line(account, cents, 0, label);
        }

        public static Line credit(String account, long cents, String label) {
            return new Line(account, 0, cents, label);
        }
    }

    public long totalDebit() {
        return lines.stream().mapToLong(Line::debitCents).sum();
    }

    public long totalCredit() {
        return lines.stream().mapToLong(Line::creditCents).sum();
    }

    /** Same amounts on the opposite side: cancels this entry's effect. */
    public EntryDraft reversed(LocalDate on, String reversalLabel) {
        return new EntryDraft(journal, on, reversalLabel, lines.stream()
                .map(l -> new Line(l.account(), l.creditCents(), l.debitCents(), l.label()))
                .toList());
    }

    /** Builder that drops zero lines and merges same-account lines, so callers can be naive. */
    public static Builder builder(Journal journal, LocalDate date, String label) {
        return new Builder(journal, date, label);
    }

    public static final class Builder {

        private final Journal journal;
        private final LocalDate date;
        private final String label;
        private final List<Line> lines = new ArrayList<>();

        private Builder(Journal journal, LocalDate date, String label) {
            this.journal = journal;
            this.date = date;
            this.label = label;
        }

        public Builder debit(String account, long cents, String lineLabel) {
            if (cents > 0) {
                lines.add(Line.debit(account, cents, lineLabel));
            } else if (cents < 0) {
                lines.add(Line.credit(account, -cents, lineLabel));
            }
            return this;
        }

        public Builder credit(String account, long cents, String lineLabel) {
            return debit(account, -cents, lineLabel);
        }

        public EntryDraft build() {
            return new EntryDraft(journal, date, label, lines);
        }
    }
}
