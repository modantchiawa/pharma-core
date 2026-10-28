package com.pharmacore.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

class DailyDigestTest {

    private static final LongFunction<String> XOF = cents -> String.format("%,d F", cents / 100).replace(',', ' ');

    private static DailyDigest digest(long previous) {
        return new DailyDigest(LocalDate.of(2026, 10, 27), "Pharmacie de l'Unité", 63, 41_250_000, previous,
                11_550_000, 654_800, 1,
                List.of(new DailyDigest.Payment("Espèces", 30_000_000), new DailyDigest.Payment("Mobile money", 11_250_000)),
                0, 500_000, 1, 2, 3, 2, 125_000_000, 7, 4);
    }

    @Test
    void summaryReadsLikeATextMessageAndFitsTheAlertFeed() {
        String summary = digest(38_000_000).summary(XOF);

        assertThat(summary).startsWith("CA 412 500 F (+9 % vs veille) · 63 vente(s), panier 6 548 F · marge 28 %")
                .contains("Espèces 300 000 F · Mobile money 112 500 F")
                .contains("1 annulée(s)")
                .contains("écart de caisse 0 F")
                .contains("3 rupture(s), 2 sous le seuil")
                .contains("2 lot(s) périment sous 90 j")
                .contains("créances 1 250 000 F (7 client(s))")
                .endsWith("4 message(s) patient à envoyer");
        assertThat(summary.length()).isLessThanOrEqualTo(DailyDigest.ALERT_MAX);
    }

    @Test
    void noComparisonWithoutYesterdayAndMessageHasOneLinePerSubject() {
        DailyDigest digest = digest(0);

        assertThat(digest.variationPercent()).isNull();
        assertThat(digest.summary(XOF)).startsWith("CA 412 500 F · ");
        String message = digest.message(XOF);
        assertThat(message.lines()).hasSize(10);
        assertThat(message).startsWith("Pharmacie de l'Unité — rapport du 2026-10-27\nChiffre d'affaires : 412 500 F\n");
    }

    @Test
    void summaryNeverExceedsTheFeedLimit() {
        var digest = new DailyDigest(LocalDate.EPOCH, "P", 1, 1, 1, 1, 1, 1,
                java.util.stream.IntStream.range(0, 40)
                        .mapToObj(i -> new DailyDigest.Payment("Opérateur numéro " + i, 1_000_000L * i)).toList(),
                1, 1, 1, 1, 1, 1, 1, 1, 1);

        assertThat(digest.summary(XOF).length()).isLessThanOrEqualTo(DailyDigest.ALERT_MAX);
    }
}
