package com.pharmacore.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSmsParserTest {

    @Test
    void readsTheUsualOperatorMessages() {
        PaymentNotice airtel = PaymentSmsParser.parse("AirtelMoney",
                "Vous avez recu 5 000 FCFA de 90123456 HADIZA MOUSSA. Trans ID: PP251027.1432.A12345. Nouveau solde: 125 000 FCFA.",
                List.of());
        assertThat(airtel.provider()).isEqualTo("AIRTEL_MONEY");
        assertThat(airtel.amountCents()).isEqualTo(5_000);
        assertThat(airtel.reference()).isEqualTo("PP251027.1432.A12345");
        assertThat(airtel.payerPhone()).isEqualTo("90123456");
        assertThat(airtel.usable()).isTrue();

        PaymentNotice moov = PaymentSmsParser.parse("Flooz",
                "Vous avez reçu un transfert de 2500F de +227 96 11 22 33. Ref: 240926001234. Solde: 30 000F",
                List.of());
        assertThat(moov.provider()).isEqualTo("MOOV_MONEY");
        assertThat(moov.amountCents()).isEqualTo(2_500);
        assertThat(moov.reference()).isEqualTo("240926001234");
        assertThat(moov.payerPhone()).isEqualTo("22796112233");

        PaymentNotice orange = PaymentSmsParser.parse("OrangeMoney",
                "Vous avez recu 12.500 FCFA de ISSA (227 94 00 00 01). ID Transaction: MP2510271012AB",
                List.of());
        assertThat(orange.provider()).isEqualTo("ORANGE_MONEY");
        assertThat(orange.amountCents()).isEqualTo(12_500);
        assertThat(orange.reference()).isEqualTo("MP2510271012AB");
    }

    @Test
    void ignoresOutgoingAndBalanceMessages() {
        assertThat(PaymentSmsParser.parse("AirtelMoney",
                "Vous avez envoye 10 000 FCFA a 90000000. Trans ID: PP1.2.3456. Frais: 100 FCFA", List.of()).usable())
                .isFalse();
        assertThat(PaymentSmsParser.parse("Zamani", "Votre solde est de 45 000 FCFA.", List.of()).usable()).isFalse();
        assertThat(PaymentSmsParser.parse("Promo", "Gagnez 1 000 000 FCFA avec notre tombola !", List.of()).usable())
                .isFalse();
        assertThat(PaymentSmsParser.parse(null, "   ", List.of())).isEqualTo(PaymentNotice.UNREADABLE);
    }

    @Test
    void pharmacyPatternsTakePrecedence() {
        var custom = new PaymentSmsParser.CustomPattern("MYNITA",
                "Credit de (?<amount>[0-9 ]+) F .*? code (?<reference>[A-Z0-9]+) .*? de (?<phone>[0-9 ]{8,})");
        PaymentNotice notice = PaymentSmsParser.parse("MyNita",
                "MyNITA: Credit de 7 500 F recu, code NT77AB12 valide, de 91 22 33 44.", List.of(custom));

        assertThat(notice.provider()).isEqualTo("MYNITA");
        assertThat(notice.amountCents()).isEqualTo(7_500);
        assertThat(notice.reference()).isEqualTo("NT77AB12");
        assertThat(notice.payerPhone()).isEqualTo("91223344");
        assertThat(notice.usable()).isTrue();
        assertThat(PaymentSmsParser.validate(List.of(custom))).isEmpty();
        assertThat(PaymentSmsParser.validate(List.of(new PaymentSmsParser.CustomPattern(null, "(unclosed"))))
                .hasSize(1);
        assertThat(PaymentSmsParser.validate(List.of(new PaymentSmsParser.CustomPattern(null, "no group"))))
                .singleElement().asString().contains("amount");
    }

    @Test
    void matchesByReferenceThenByUniqueAmount() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        var pending = List.of(
                new PaymentMatching.Candidate(a, "AIRTEL_MONEY", 5_000, null),
                new PaymentMatching.Candidate(b, "AIRTEL_MONEY", 5_000, "PP9.9.9"),
                new PaymentMatching.Candidate(c, "MOOV_MONEY", 2_500, null));

        assertThat(PaymentMatching.choose(new PaymentNotice("AIRTEL_MONEY", 5_000L, "PP9.9.9", null, true), pending))
                .contains(b);
        // no reference typed at the counter: the only untyped 5 000 F Airtel payment
        assertThat(PaymentMatching.choose(new PaymentNotice("AIRTEL_MONEY", 5_000L, "PP1.1.1", null, true), pending))
                .contains(a);
        assertThat(PaymentMatching.choose(new PaymentNotice("MOOV_MONEY", 2_500L, null, null, true), pending))
                .contains(c);
        // unknown operator, amount unique across operators
        assertThat(PaymentMatching.choose(new PaymentNotice(null, 2_500L, null, null, true), pending)).contains(c);
        // two untyped candidates of the same amount: nobody is guessed
        var twins = List.of(new PaymentMatching.Candidate(a, "AIRTEL_MONEY", 5_000, null),
                new PaymentMatching.Candidate(b, "AIRTEL_MONEY", 5_000, null));
        assertThat(PaymentMatching.choose(new PaymentNotice("AIRTEL_MONEY", 5_000L, null, null, true), twins))
                .isEqualTo(Optional.empty());
        assertThat(PaymentMatching.choose(new PaymentNotice("AIRTEL_MONEY", 5_000L, null, null, false), pending))
                .isEmpty();
    }
}
