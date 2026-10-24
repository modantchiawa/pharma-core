package com.pharmacore.printing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pharmacore.printing.domain.ReceiptDocument.Certification;
import com.pharmacore.printing.domain.ReceiptDocument.Header;
import com.pharmacore.printing.domain.ReceiptDocument.Line;
import com.pharmacore.printing.domain.ReceiptDocument.Payment;
import com.pharmacore.printing.domain.ReceiptDocument.VatRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class EscPosRendererTest {

    private static final byte[] INIT = {0x1b, '@'};
    private static final byte[] PARTIAL_CUT = {0x1d, 'V', 66, 0};
    private static final byte[] DRAWER = {0x1b, 'p', 0, 25, (byte) 250};
    private static final byte[] QR_PRINT = {0x1d, '(', 'k', 3, 0, 49, 81, 48};

    static ReceiptDocument document(Certification certification) {
        return new ReceiptDocument(
                new Header("Pharmacie de l'Unité", "Rue du Marché, Niamey", "+227 20 00 00 00", "NIF123",
                        "Ouvert 7j/7", "Merci de votre visite — à bientôt"),
                "T1-000042", LocalDateTime.of(2026, 10, 24, 9, 30), "T1", "Aïcha",
                new ReceiptDocument.Customer("Mme Hadiza Moussa", "+227 90 00 00 00", null, "NIA 12345"),
                List.of(new Line("PARACETAMOL 500MG CPR B/16 BOITE GRAND FORMAT", 2, 600, 0, 1_200, new BigDecimal("0")),
                        new Line("AMOXICILLINE 1G CPR", 1, 2_000, 200, 1_800, new BigDecimal("19"))),
                200, 3_000, List.of(new Payment("Espèces", 2_000), new Payment("Mobile money airtel money", 1_000)),
                0, List.of(new VatRow(new BigDecimal("0"), 1_200, 0), new VatRow(new BigDecimal("19"), 1_800, 287)),
                certification, false, false, "XOF");
    }

    @Test
    void initialisesCutsAndKicksTheDrawerWhenAsked() {
        byte[] bytes = new EscPosRenderer(48, true, true).render(document(null));

        assertThat(bytes).startsWith(INIT);
        assertThat(indexOf(bytes, PARTIAL_CUT)).isPositive();
        assertThat(indexOf(bytes, DRAWER)).isGreaterThan(indexOf(bytes, PARTIAL_CUT));
        assertThat(indexOf(bytes, QR_PRINT)).isNegative();
    }

    @Test
    void noCutNoDrawerWhenDisabled() {
        byte[] bytes = new EscPosRenderer(32, false, false).render(document(null));

        assertThat(indexOf(bytes, PARTIAL_CUT)).isNegative();
        assertThat(indexOf(bytes, DRAWER)).isNegative();
    }

    @Test
    void columnsFitTheWidthAndTheTotalIsRightAligned() {
        for (int width : new int[] {32, 48}) {
            List<ReceiptLayout.Row> rows = ReceiptLayout.rows(document(null), width);
            assertThat(rows).allSatisfy(row -> assertThat(row.text().length()).isLessThanOrEqualTo(width));
            ReceiptLayout.Row total = rows.stream().filter(r -> r.text().startsWith("TOTAL TTC")).findFirst().orElseThrow();
            assertThat(total.text()).hasSize(width).endsWith("3 000 F");
            assertThat(total.style()).isEqualTo(ReceiptLayout.Style.LARGE);
        }
        // a long designation wraps instead of being cut
        List<String> narrow = ReceiptLayout.rows(document(null), 32).stream().map(ReceiptLayout.Row::text).toList();
        assertThat(narrow).contains("PARACETAMOL 500MG CPR B/16 BOITE").contains("GRAND FORMAT");
    }

    @Test
    void accentsAreTransliteratedWithoutACodePageAndKeptWithOne() {
        byte[] ascii = new EscPosRenderer(48, false, false, null).render(document(null));
        String text = new String(ascii, StandardCharsets.US_ASCII);
        assertThat(text).contains("Pharmacie de l'Unite").contains("Especes").contains("Merci de votre visite - a bientot");
        for (byte b : ascii) {
            assertThat(b & 0x80).as("7-bit ASCII only").isZero();
        }

        EscPosRenderer cp858 = new EscPosRenderer(48, false, false);
        if (!cp858.asciiOnly()) {
            byte[] encoded = cp858.render(document(null));
            assertThat(indexOf(encoded, new byte[] {0x1b, 't', 19})).isPositive();
            assertThat(encoded).contains((byte) 0x82); // 'é' in code page 858
        }
    }

    @Test
    void certifiedSaleCarriesAQrCodeAndCancelledSaleSaysSo() {
        byte[] certified = new EscPosRenderer(48, true, false)
                .render(document(new Certification("NE-2026-000042", "https://secef.ne/v/NE-2026-000042")));
        assertThat(indexOf(certified, QR_PRINT)).isPositive();
        assertThat(new String(certified, StandardCharsets.ISO_8859_1)).contains("https://secef.ne/v/NE-2026-000042")
                .contains("Facture certifiee NE-2026-000042");

        ReceiptDocument base = document(null);
        var cancelled = new ReceiptDocument(base.header(), base.ticketNumber(), base.issuedAt(), base.terminalId(),
                base.cashier(), base.customer(), base.lines(), base.discountCents(), base.netCents(), base.payments(),
                base.changeDueCents(), base.vat(), null, false, true, base.currency());
        assertThat(new TextReceiptRenderer(32).render(cancelled)).contains("*** VENTE ANNULEE ***");
    }

    @Test
    void moneyFollowsTheCurrency() {
        assertThat(ReceiptLayout.money(1_234_500, "XOF")).isEqualTo("1 234 500 F");
        assertThat(ReceiptLayout.money(0, "XOF")).isEqualTo("0 F");
        assertThat(ReceiptLayout.money(-2_500, "XOF")).isEqualTo("-2 500 F");
        assertThat(ReceiptLayout.money(1_250, "EUR")).isEqualTo("12,50 EUR");
        assertThatThrownBy(() -> new EscPosRenderer(40, true, false)).isInstanceOf(IllegalArgumentException.class);
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
