package com.pharmacore.printing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextReceiptRendererTest {

    @Test
    void rendersTheWholeTicketCentredHeaderAndAlignedAmounts() {
        String text = new TextReceiptRenderer(48).render(EscPosRendererTest.document(null));
        String[] lines = text.split("\n");

        assertThat(lines[0].strip()).isEqualTo("Pharmacie de l'Unité");
        assertThat(lines[0]).startsWith("              "); // centred on 48 columns
        assertThat(text)
                .contains("Ticket T1-000042")
                .contains("24/10/2026 09:30  Caisse T1")
                .contains("Vendeur Aïcha")
                .contains("Client Mme Hadiza Moussa")
                .contains("Police NIA 12345")
                .contains(" 2 x 600 F")
                .contains("  Remise")
                .contains("Total remises")
                .contains("TOTAL TTC")
                .contains("Espèces")
                .contains("Mobile money airtel money")
                .contains("19 %")
                .contains("Merci de votre visite");
        assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(48));
        String total = java.util.Arrays.stream(lines).filter(l -> l.startsWith("TOTAL TTC")).findFirst().orElseThrow();
        assertThat(total).endsWith("3 000 F").hasSize(48);
    }

    @Test
    void narrowTicketKeepsEveryAmountVisible() {
        String text = new TextReceiptRenderer(32).render(EscPosRendererTest.document(null));
        assertThat(text.split("\n")).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(32));
        assertThat(text).contains("1 800 F").contains("1 200 F").contains("2 000 F");
    }
}
