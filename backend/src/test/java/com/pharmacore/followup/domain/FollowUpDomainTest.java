package com.pharmacore.followup.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FollowUpDomainTest {

    @Test
    void normalisesLocalAndInternationalNumbersToTheCountry() {
        assertThat(PhoneNumbers.international("90 12 34 56", "NE")).contains("22790123456");
        assertThat(PhoneNumbers.international("+227 90 12 34 56", "NE")).contains("22790123456");
        assertThat(PhoneNumbers.international("00221 77 123 45 67", "NE")).contains("221771234567");
        assertThat(PhoneNumbers.international("07 12 34 56 78", "CI")).contains("2250712345678");
        assertThat(PhoneNumbers.international("22790123456", "NE")).contains("22790123456");
        assertThat(PhoneNumbers.international("12", "NE")).isEmpty();
        assertThat(PhoneNumbers.international(null, "NE")).isEmpty();
        assertThat(PhoneNumbers.callingCode("XX")).isEqualTo("227");
    }

    @Test
    void buildsAClickToChatLinkWithTheTextEncoded() {
        assertThat(PhoneNumbers.whatsappLink("22790123456", "Bonjour Hadiza, à bientôt & merci"))
                .isEqualTo("https://wa.me/22790123456?text=Bonjour%20Hadiza%2C%20%C3%A0%20bient%C3%B4t%20%26%20merci");
    }

    @Test
    void rendersTemplatesAndKeepsUnknownPlaceholders() {
        String body = MessageTemplates.render(MessageKind.PRODUCT_AVAILABLE.defaultTemplate(), Map.of(
                "client", "Hadiza", "produit", "DOLIPRANE 1000", "pharmacie", "Pharmacie de l'Unité",
                "telephone", "+227 20 00 00 00"));

        assertThat(body).isEqualTo("Bonjour Hadiza, bonne nouvelle : DOLIPRANE 1000 est de nouveau disponible chez "
                + "Pharmacie de l'Unité. Nous vous le gardons quelques jours. Tél. +227 20 00 00 00");
        assertThat(MessageTemplates.render("Solde {montant} {inconnu}", Map.of("montant", "1 250 F")))
                .isEqualTo("Solde 1 250 F {inconnu}");
        assertThat(MessageTemplates.render("x".repeat(700), Map.of())).hasSize(MessageTemplates.MAX_BODY);
        assertThat(MessageTemplates.render(null, Map.of())).isEmpty();
    }
}
