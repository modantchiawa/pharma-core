package com.pharmacore.printing.domain;

import com.pharmacore.printing.domain.ReceiptLayout.Align;
import com.pharmacore.printing.domain.ReceiptLayout.Row;
import java.util.stream.Collectors;

/**
 * The same ticket as plain text: what the ESC/POS printer prints, minus the control codes.
 * Used for previews, e-mail or WhatsApp copies and the tests of the layout.
 */
public final class TextReceiptRenderer {

    private final int width;

    public TextReceiptRenderer(int width) {
        this.width = width;
    }

    public String render(ReceiptDocument document) {
        return ReceiptLayout.rows(document, width).stream()
                .map(this::line)
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private String line(Row row) {
        if (row.align() == Align.CENTER && row.text().length() < width) {
            return " ".repeat((width - row.text().length()) / 2) + row.text();
        }
        return row.text();
    }
}
