package com.pharmacore.printing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Fixed-width layout shared by the ESC/POS and plain text renderers: the same lines, columns and
 * amount format whatever the printer, so a 58 mm ticket (32 columns) and an 80 mm ticket
 * (48 columns) only differ by their width.
 */
public final class ReceiptLayout {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private ReceiptLayout() {
    }

    /** A formatted line with the emphasis the renderer may apply. */
    public record Row(String text, Style style, Align align) {
    }

    public enum Style { NORMAL, BOLD, LARGE }

    public enum Align { LEFT, CENTER }

    public static List<Row> rows(ReceiptDocument document, int width) {
        if (width < 24) {
            throw new IllegalArgumentException("A ticket needs at least 24 columns");
        }
        var rows = new ArrayList<Row>();
        var header = document.header();
        rows.add(new Row(fit(header.displayName(), width), Style.BOLD, Align.CENTER));
        wrap(header.addressLine(), width).forEach(line -> rows.add(new Row(line, Style.NORMAL, Align.CENTER)));
        if (notBlank(header.phone())) {
            rows.add(new Row(fit("Tel. " + header.phone(), width), Style.NORMAL, Align.CENTER));
        }
        if (notBlank(header.legalIdentifier())) {
            rows.add(new Row(fit("NIF " + header.legalIdentifier(), width), Style.NORMAL, Align.CENTER));
        }
        wrap(header.receiptHeader(), width).forEach(line -> rows.add(new Row(line, Style.NORMAL, Align.CENTER)));
        rows.add(rule(width));
        if (document.cancelled()) {
            rows.add(new Row(fit("*** VENTE ANNULEE ***", width), Style.LARGE, Align.CENTER));
        }
        rows.add(new Row(fit("Ticket " + document.ticketNumber(), width), Style.BOLD, Align.LEFT));
        rows.add(new Row(fit(DATE.format(document.issuedAt()) + "  Caisse " + document.terminalId(), width),
                Style.NORMAL, Align.LEFT));
        rows.add(new Row(fit("Vendeur " + document.cashier(), width), Style.NORMAL, Align.LEFT));
        if (document.customer() != null) {
            var customer = document.customer();
            rows.add(new Row(fit("Client " + customer.displayName(), width), Style.NORMAL, Align.LEFT));
            if (notBlank(customer.policy())) {
                rows.add(new Row(fit("Police " + customer.policy(), width), Style.NORMAL, Align.LEFT));
            }
        }
        rows.add(rule(width));
        String currency = document.currency();
        for (var line : document.lines()) {
            wrap(line.designation(), width).forEach(text -> rows.add(new Row(text, Style.NORMAL, Align.LEFT)));
            String detail = line.quantity() + " x " + money(line.unitPriceCents(), currency);
            rows.add(new Row(columns(" " + detail, money(line.netCents(), currency), width), Style.NORMAL, Align.LEFT));
            if (line.discountCents() > 0) {
                rows.add(new Row(columns("  Remise", "-" + money(line.discountCents(), currency), width),
                        Style.NORMAL, Align.LEFT));
            }
            if (notBlank(line.posology())) {
                wrap("  > " + line.posology(), width).forEach(text -> rows.add(new Row(text, Style.BOLD, Align.LEFT)));
            }
        }
        rows.add(rule(width));
        if (document.discountCents() > 0) {
            rows.add(new Row(columns("Total remises", "-" + money(document.discountCents(), currency), width),
                    Style.NORMAL, Align.LEFT));
        }
        rows.add(new Row(columns("TOTAL TTC", money(document.netCents(), currency), width), Style.LARGE, Align.LEFT));
        for (var payment : document.payments()) {
            rows.add(new Row(columns(payment.label(), money(payment.amountCents(), currency), width),
                    Style.NORMAL, Align.LEFT));
        }
        if (document.changeDueCents() > 0) {
            rows.add(new Row(columns("Rendu", money(document.changeDueCents(), currency), width),
                    Style.NORMAL, Align.LEFT));
        }
        if (!document.vat().isEmpty()) {
            rows.add(rule(width));
            rows.add(new Row(columns("TVA", "TTC / dont TVA", width), Style.NORMAL, Align.LEFT));
            for (var row : document.vat()) {
                rows.add(new Row(columns(rate(row.rate()) + " %",
                        money(row.totalTtcCents(), currency) + " / " + money(row.vatCents(), currency), width),
                        Style.NORMAL, Align.LEFT));
            }
        }
        if (document.certification() != null && notBlank(document.certification().code())) {
            rows.add(rule(width));
            rows.add(new Row(fit("Facture certifiee " + document.certification().code(), width), Style.NORMAL,
                    Align.CENTER));
        }
        if (document.offline()) {
            rows.add(new Row(fit("Vente enregistree hors-ligne", width), Style.NORMAL, Align.CENTER));
        }
        wrap(header.receiptFooter(), width).forEach(line -> rows.add(new Row(line, Style.NORMAL, Align.CENTER)));
        return rows;
    }

    /** Amount in minor units: 12500 → "12 500 F" in XOF, 1250 → "12,50 EUR" in EUR. */
    public static String money(long minor, String currency) {
        int exponent;
        try {
            exponent = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            exponent = 0;
        }
        BigDecimal value = BigDecimal.valueOf(minor).movePointLeft(exponent).setScale(exponent, RoundingMode.UNNECESSARY);
        String digits = value.abs().toPlainString();
        int dot = digits.indexOf('.');
        String integer = dot < 0 ? digits : digits.substring(0, dot);
        String fraction = dot < 0 ? "" : "," + digits.substring(dot + 1);
        var grouped = new StringBuilder();
        for (int i = 0; i < integer.length(); i++) {
            if (i > 0 && (integer.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(integer.charAt(i));
        }
        return (minor < 0 ? "-" : "") + grouped + fraction + " " + ("XOF".equals(currency) ? "F" : currency);
    }

    static String rate(BigDecimal rate) {
        BigDecimal stripped = rate.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toPlainString()
                : rate.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Left label and right-aligned value on one line; the label is cut, never the value. */
    static String columns(String left, String right, int width) {
        int room = width - right.length() - 1;
        String label = room <= 0 ? "" : fit(left, room);
        return label + " ".repeat(Math.max(1, width - label.length() - right.length())) + right;
    }

    static Row rule(int width) {
        return new Row("-".repeat(width), Style.NORMAL, Align.LEFT);
    }

    static String fit(String text, int width) {
        String value = text == null ? "" : text.stripTrailing();
        return value.length() <= width ? value : value.substring(0, width);
    }

    static List<String> wrap(String text, int width) {
        var lines = new ArrayList<String>();
        if (!notBlank(text)) {
            return lines;
        }
        for (String paragraph : text.strip().split("\\R")) {
            var current = new StringBuilder();
            for (String word : paragraph.strip().split("\\s+")) {
                String token = word.length() > width ? word.substring(0, width) : word;
                if (current.length() + token.length() + (current.isEmpty() ? 0 : 1) > width) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(token);
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
        }
        return lines;
    }

    /** "Électricité – 1 250 €" → "Electricite - 1 250 E" for printers without an accented code page. */
    public static String transliterate(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        var out = new StringBuilder(decomposed.length());
        for (char c : decomposed.toCharArray()) {
            out.append(switch (c) {
                case '€' -> 'E';
                case 'œ' -> 'o';
                case 'Œ' -> 'O';
                case 'æ' -> 'a';
                case 'Æ' -> 'A';
                case 'ß' -> 's';
                case '–', '—', '‑' -> '-';
                case '’', '‘' -> '\'';
                case '«', '»', '“', '”' -> '"';
                case '…' -> '.';
                case ' ', ' ' -> ' ';
                default -> c < 128 ? c : (Character.isLetterOrDigit(c) ? '?' : ' ');
            });
        }
        return out.toString();
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
