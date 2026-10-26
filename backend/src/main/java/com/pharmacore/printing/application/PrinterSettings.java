package com.pharmacore.printing.application;

/**
 * How one cash register prints. {@code BROWSER} lets the terminal print through the operating
 * system's dialog (any printer, 58 mm, 80 mm or A4 page size chosen by {@code paper}); the two
 * ESC/POS modes send raw bytes from the server to a thermal printer, which is faster, needs no
 * dialog and works for tablets without a printer driver.
 */
public record PrinterSettings(String terminalId,
                              Mode mode,
                              Paper paper,
                              String address,
                              int widthChars,
                              int copies,
                              boolean cut,
                              boolean drawerKick,
                              boolean autoPrint,
                              InvoicePaper invoicePaper,
                              boolean configured) {

    public enum Mode { BROWSER, ESCPOS_NETWORK, ESCPOS_SHARED }

    public enum Paper { MM58, MM80, A4 }

    public enum InvoicePaper { A4, NONE }

    public PrinterSettings {
        if (terminalId == null || terminalId.isBlank()) {
            throw new IllegalArgumentException("A terminal id is required");
        }
        if (widthChars != 32 && widthChars != 42 && widthChars != 48) {
            throw new IllegalArgumentException("Width must be 32, 42 or 48 columns");
        }
        if (copies < 1 || copies > 3) {
            throw new IllegalArgumentException("Between 1 and 3 copies");
        }
        if (mode != Mode.BROWSER && (address == null || address.isBlank())) {
            throw new IllegalArgumentException("An ESC/POS printer needs an address");
        }
        if (mode != Mode.BROWSER && paper == Paper.A4) {
            throw new IllegalArgumentException("A thermal printer takes 58 mm or 80 mm paper");
        }
    }

    /** What a terminal uses before anyone configured it: the browser dialog on 80 mm paper. */
    public static PrinterSettings defaults(String terminalId) {
        return new PrinterSettings(terminalId, Mode.BROWSER, Paper.MM80, null, 48, 1, true, false, true,
                InvoicePaper.A4, false);
    }

    public boolean escPos() {
        return mode != Mode.BROWSER;
    }
}
