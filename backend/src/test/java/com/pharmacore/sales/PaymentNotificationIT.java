package com.pharmacore.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PaymentNotificationIT extends IntegrationTest {

    @Test
    void forwardedSmsConfirmsThePendingMobileMoneyPaymentAndIsTakenOnce() throws Exception {
        Pharmacy pharmacy = onboardPharmacy("Pharmacie Mobile");
        UUID product = createProduct("PARACETAMOL 1000", "NONE", 5_000);
        receiveLot(pharmacy, product, "L-1", LocalDate.now().plusYears(1).toString(), 50);
        String token = read(call(post("/api/v1/payments/channel/token"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hint").isString()), "$.token");
        assertThat(token).hasSize(32);
        call(get("/api/v1/payments/channel"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenSet").value(true))
                .andExpect(jsonPath("$.values.autoConfirm").value(true));

        // the counter records a 5 000 F Airtel payment, pending the operator's confirmation
        String saleId = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, "AIRTEL_MONEY", 5_000, null))
                .andExpect(status().isCreated()), "$.saleId");
        call(get("/api/v1/payments/pending?status=PENDING"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(1));

        // the phone forwards the operator's SMS to the server: wrong token first, then the right one
        mvc.perform(post("/api/public/payments/" + pharmacy.slug() + "/sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PharmaCore-Token", "WRONGTOKEN")
                        .content(sms("AirtelMoney", "Vous avez recu 5 000 FCFA de 90123456. Trans ID: PP251027.1.A1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/public/payments/" + pharmacy.slug() + "/sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PharmaCore-Token", token)
                        .content(sms("AirtelMoney", "Vous avez recu 5 000 FCFA de 90123456. Trans ID: PP251027.1.A1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.matched").value("MATCHED"));
        call(get("/api/v1/payments/pending?status=CONFIRMED"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].saleId").value(saleId));

        // the phone retries the same SMS within the minute: nothing new
        mvc.perform(post("/api/public/payments/" + pharmacy.slug() + "/sms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-PharmaCore-Token", token)
                        .content(sms("AirtelMoney", "Vous avez recu 5 000 FCFA de 90123456. Trans ID: PP251027.1.A1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        // an outgoing transfer is kept for the record but never matched
        mvc.perform(post("/api/public/payments/" + pharmacy.slug() + "/sms")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", token)
                        .param("from", "AirtelMoney")
                        .param("text", "Vous avez envoye 20 000 FCFA a 96000000. Trans ID: PP251027.2.B2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value("IGNORED"));

        String feed = body(call(get("/api/v1/payments/notifications"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(0)));
        List<String> statuses = JsonPath.read(feed, "$.notifications[*].status");
        assertThat(statuses).containsExactlyInAnyOrder("MATCHED", "IGNORED");
        List<String> tickets = JsonPath.read(feed, "$.notifications[?(@.status == 'MATCHED')].ticketNumber");
        assertThat(tickets).hasSize(1);
    }

    @Test
    void confirmationArrivingBeforeTheSaleSettlesItWhenTheSaleIsReplayed() throws Exception {
        Pharmacy pharmacy = onboardPharmacy("Pharmacie Hors Ligne");
        UUID product = createProduct("AMOXICILLINE 500", "NONE", 2_500);
        receiveLot(pharmacy, product, "L-1", LocalDate.now().plusYears(1).toString(), 50);

        // the cashier reads the SMS on the phone and types it on a terminal while the sale is still
        // in the terminal's journal; a replay of the same entry is idempotent
        UUID clientId = UUID.randomUUID();
        String manual = """
                {"clientId":"%s","provider":"MOOV_MONEY","amountCents":2500,"reference":"240926001234",
                 "payerPhone":"96112233","rawText":"Vous avez recu 2500F de 96112233. Ref: 240926001234","terminalId":"T-2"}"""
                .formatted(clientId);
        String notificationId = read(call(post("/api/v1/payments/notifications"), pharmacy.ownerToken(), manual)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNMATCHED")), "$.id");
        call(post("/api/v1/payments/notifications"), pharmacy.ownerToken(), manual)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notificationId));

        // the sale reaches the server later, with the reference typed from the SMS
        call(post("/api/v1/pos/sync/sales"), pharmacy.ownerToken(), """
                {"sales":[%s]}""".formatted(sale(product, "MOOV_MONEY", 2_500, "240926001234")))
                .andExpect(status().isOk());
        call(get("/api/v1/payments/notifications?status=MATCHED"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.notifications[0].id").value(notificationId));
        call(get("/api/v1/payments/pending?status=CONFIRMED"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reference").value("240926001234"));

        // two pending payments of the same amount: nobody is guessed, the team chooses
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, "MOOV_MONEY", 2_500, null))
                .andExpect(status().isCreated());
        String second = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, "MOOV_MONEY", 2_500, null))
                .andExpect(status().isCreated()), "$.saleId");
        String open = read(call(post("/api/v1/payments/notifications"), pharmacy.ownerToken(), """
                {"clientId":"%s","provider":"MOOV_MONEY","amountCents":2500,"reference":"240926009999"}"""
                .formatted(UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNMATCHED")), "$.id");
        String pending = body(call(get("/api/v1/payments/pending?status=PENDING"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(2)));
        String secondPayment = JsonPath.<List<String>>read(pending, "$[?(@.saleId == '" + second + "')].paymentId").getFirst();
        call(post("/api/v1/payments/notifications/" + open + "/claim"), pharmacy.ownerToken(), """
                {"paymentId":"%s"}""".formatted(secondPayment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.matchedBy").value("Dr Pharmacie Hors Ligne"));
        call(post("/api/v1/payments/notifications/" + open + "/ignore"), pharmacy.ownerToken(), null)
                .andExpect(status().is(422));

        // a pattern typed by the pharmacy must compile and name the amount
        call(put("/api/v1/payments/channel"), pharmacy.ownerToken(), """
                {"autoConfirm":true,"matchWindowHours":48,"patterns":[{"provider":"MYNITA","regex":"(unclosed"}]}""")
                .andExpect(status().is(422));
        call(put("/api/v1/payments/channel"), pharmacy.ownerToken(), """
                {"autoConfirm":false,"matchWindowHours":24,
                 "patterns":[{"provider":"MYNITA","regex":"Credit de (?<amount>[0-9 ]+) F.*code (?<reference>[A-Z0-9]+)"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.autoConfirm").value(false))
                .andExpect(jsonPath("$.values.patterns.length()").value(1));

        // another pharmacy sees nothing of this
        Pharmacy other = onboardPharmacy("Pharmacie Voisine");
        call(get("/api/v1/payments/notifications"), other.ownerToken(), null)
                .andExpect(jsonPath("$.notifications.length()").value(0));
    }

    private static String sale(UUID product, String provider, long amountCents, String reference) {
        UUID id = UUID.randomUUID();
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":1,"unitPriceCents":%d,"discountCents":0}],
                 "payments":[{"method":"MOBILE_MONEY","amountCents":%d,"provider":"%s"%s}]}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(), product, amountCents, amountCents,
                provider, reference == null ? "" : ",\"reference\":\"" + reference + "\"");
    }

    private static String sms(String from, String text) {
        return """
                {"from":"%s","text":"%s","sentStamp":%d}""".formatted(from, text, System.currentTimeMillis());
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
