package com.pharmacore.printing.domain;

import com.pharmacore.printing.domain.ReceiptLayout.Align;
import com.pharmacore.printing.domain.ReceiptLayout.Row;
import com.pharmacore.printing.domain.ReceiptLayout.Style;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * ESC/POS byte stream for 58 mm (32 columns) and 80 mm (48 columns) thermal printers: Epson-style
 * commands understood by the receipt printers sold with point-of-sale terminals (Epson, Xprinter,
 * Sunmi, Rongta, generic POS-58/POS-80). Text is encoded in code page 858 (Western Europe with
 * the euro sign) when the JVM knows it, otherwise transliterated to ASCII so accents never print as
 * garbage.
 */
public final class EscPosRenderer {

    /** Printer code page selected with ESC t: 19 = PC858 on Epson printers. */
    private static final int CODE_PAGE_858 = 19;

    public static final byte ESC = 0x1b;
    public static final byte GS = 0x1d;

    private final int width;
    private final boolean cut;
    private final boolean drawerKick;
    private final Charset charset;
    private final boolean asciiOnly;

    /**
     * @param width      printable columns: 32 for 58 mm paper, 42 or 48 for 80 mm paper
     * @param cut        send a partial cut at the end
     * @param drawerKick pulse the cash drawer connected to the printer
     */
    public EscPosRenderer(int width, boolean cut, boolean drawerKick) {
        this(width, cut, drawerKick, codePage());
    }

    EscPosRenderer(int width, boolean cut, boolean drawerKick, Charset charset) {
        if (width != 32 && width != 42 && width != 48) {
            throw new IllegalArgumentException("Supported widths: 32, 42, 48 columns");
        }
        this.width = width;
        this.cut = cut;
        this.drawerKick = drawerKick;
        this.charset = charset == null ? StandardCharsets.US_ASCII : charset;
        this.asciiOnly = charset == null;
    }

    static Charset codePage() {
        for (String name : new String[] {"IBM00858", "IBM858", "cp858"}) {
            if (Charset.isSupported(name)) {
                return Charset.forName(name);
            }
        }
        return null;
    }

    public int width() {
        return width;
    }

    public boolean asciiOnly() {
        return asciiOnly;
    }

    public byte[] render(ReceiptDocument document) {
        var out = new ByteArrayOutputStream();
        out.write(ESC);
        out.write('@');                       // initialise
        if (!asciiOnly) {
            out.write(ESC);
            out.write('t');
            out.write(CODE_PAGE_858);        // select the code page matching our charset
        }
        for (Row row : ReceiptLayout.rows(document, width)) {
            align(out, row.align());
            style(out, row.style());
            write(out, row.text());
            out.write('\n');
            if (row.style() != Style.NORMAL) {
                style(out, Style.NORMAL);
            }
        }
        align(out, Align.LEFT);
        if (document.certification() != null && document.certification().qrPayload() != null
                && !document.certification().qrPayload().isBlank()) {
            align(out, Align.CENTER);
            qr(out, document.certification().qrPayload());
            align(out, Align.LEFT);
        }
        feed(out, 4);
        if (cut) {
            out.write(GS);
            out.write('V');
            out.write(66);                    // partial cut
            out.write(0);
        }
        if (drawerKick) {
            out.write(ESC);
            out.write('p');
            out.write(0);                     // drawer pin 2
            out.write(25);                    // 50 ms on
            out.write((byte) 250);            // 500 ms off
        }
        return out.toByteArray();
    }

    /** Ticket printed from the settings page: proves the connection, width and character set. */
    public byte[] renderTest(String pharmacyName, String terminalId) {
        var out = new ByteArrayOutputStream();
        out.write(ESC);
        out.write('@');
        if (!asciiOnly) {
            out.write(ESC);
            out.write('t');
            out.write(CODE_PAGE_858);
        }
        align(out, Align.CENTER);
        style(out, Style.BOLD);
        write(out, ReceiptLayout.fit(pharmacyName, width));
        out.write('\n');
        style(out, Style.NORMAL);
        write(out, "Test d'impression");
        out.write('\n');
        write(out, "Poste " + terminalId);
        out.write('\n');
        align(out, Align.LEFT);
        write(out, "|" + "-".repeat(width - 2) + "|");
        out.write('\n');
        write(out, ReceiptLayout.columns("Largeur", width + " colonnes", width));
        out.write('\n');
        write(out, ReceiptLayout.columns("Accents", "éèàçù€", width));
        out.write('\n');
        write(out, ReceiptLayout.columns("Montant", ReceiptLayout.money(1_234_500, "XOF"), width));
        out.write('\n');
        feed(out, 4);
        if (cut) {
            out.write(GS);
            out.write('V');
            out.write(66);
            out.write(0);
        }
        return out.toByteArray();
    }

    private void write(ByteArrayOutputStream out, String text) {
        String value = asciiOnly ? ReceiptLayout.transliterate(text) : text;
        byte[] bytes = value.getBytes(charset);
        out.write(bytes, 0, bytes.length);
    }

    private static void align(ByteArrayOutputStream out, Align align) {
        out.write(ESC);
        out.write('a');
        out.write(align == Align.CENTER ? 1 : 0);
    }

    private static void style(ByteArrayOutputStream out, Style style) {
        out.write(ESC);
        out.write('E');
        out.write(style == Style.NORMAL ? 0 : 1);     // emphasis
        out.write(GS);
        out.write('!');
        out.write(style == Style.LARGE ? 0x11 : 0);    // double width and height
    }

    private static void feed(ByteArrayOutputStream out, int lines) {
        out.write(ESC);
        out.write('d');
        out.write(lines);
    }

    /** GS ( k model 2 QR code, module size 4, error correction M. */
    private void qr(ByteArrayOutputStream out, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        int length = data.length + 3;
        out.writeBytes(new byte[] {GS, '(', 'k', 4, 0, 49, 65, 50, 0});                 // model 2
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, 49, 67, 4});                     // module size
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, 49, 69, 49});                    // correction M
        out.writeBytes(new byte[] {GS, '(', 'k', (byte) (length & 0xff), (byte) (length >> 8), 49, 80, 48});
        out.writeBytes(data);                                                             // store
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, 49, 81, 48});                    // print
        out.write('\n');
    }
}
