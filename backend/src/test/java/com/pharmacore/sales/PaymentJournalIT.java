package com.pharmacore.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Receipts by category ("encaissements par caisse"): the MyNITA payments of a day with their
 * total and their transactions, one station or all, cancelled sales left out, a payment rejected
 * on the operator's statement reported apart, the day cut at the pharmacy's local midnight.
 */
class PaymentJournalIT extends IntegrationTest {

    private static final String API = "/api/v1/payments/journal";
    private static final ZoneId NIAMEY = ZoneId.of("Africa/Niamey");

    @Test
    void receiptsOfTheDayByCategoryAndStation() throws Exception {
        var pharmacy = onboardPharmacy("Encaissements Zinder");
        String owner = pharmacy.ownerToken();
        UUID product = createProduct("PARACETAMOL 500MG CPR B/16", "NONE", 500);
        receiveLot(pharmacy, product, "P-1", "2029-01-31", 200);
        // a few minutes ago, kept clear of the local midnight so every sale falls on the same day
        Instant at = Instant.now().minus(10, ChronoUnit.MINUTES);
        int secondOfDay = at.atZone(NIAMEY).toLocalTime().toSecondOfDay();
        if (secondOfDay < 600 || secondOfDay > 86_400 - 600) {
            at = at.minus(20, ChronoUnit.MINUTES);
        }
        LocalDate today = at.atZone(NIAMEY).toLocalDate();

        sale(owner, product, "CAISSE-1", at, payment("MOBILE_MONEY", 5_000, "MYNITA", "NITA-001"));
        sale(owner, product, "CAISSE-2", at.plusSeconds(60), payment("MOBILE_MONEY", 2_500, "MYNITA", "NITA-002"));
        String rejected = sale(owner, product, "CAISSE-1", at.plusSeconds(120),
                payment("MOBILE_MONEY", 9_000, "MYNITA", "NITA-003"));
        sale(owner, product, "CAISSE-1", at.plusSeconds(180),
                payment("MOBILE_MONEY", 1_000, "AIRTEL_MONEY", "AM-001"));
        sale(owner, product, "CAISSE-2", at.plusSeconds(240), payment("CASH", 1_500, null, null));
        String cancelled = sale(owner, product, "CAISSE-1", at.plusSeconds(300),
                payment("MOBILE_MONEY", 4_000, "MYNITA", "NITA-004"));
        // yesterday, a minute before the local midnight: not today's
        sale(owner, product, "CAISSE-1", today.atStartOfDay(NIAMEY).minus(1, ChronoUnit.MINUTES).toInstant(),
                payment("MOBILE_MONEY", 7_000, "MYNITA", "NITA-000"));

        call(post("/api/v1/sales/" + cancelled + "/cancellation"), owner, """
                {"reason":"Erreur de saisie","terminalId":"CAISSE-1"}""")
                .andExpect(status().isOk());
        String pending = call(get("/api/v1/payments/pending"), owner, null).andReturn().getResponse()
                .getContentAsString();
        String rejectedPayment = JsonPath.<List<String>>read(pending,
                "$[?(@.saleId == '" + rejected + "')].paymentId").getFirst();
        call(post("/api/v1/payments/" + rejectedPayment + "/rejection"), owner, """
                {"reason":"Absent du relevé NITA"}""")
                .andExpect(status().isOk());

        // --- the day, all stations: one line per category, the rejected payment apart
        var journal = call(get(API + "?from=" + today), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.totalCents").value(5_000 + 2_500 + 1_000 + 1_500))
                .andExpect(jsonPath("$.terminals.length()").value(2));
        String body = journal.andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(body, "$.categories[?(@.provider == 'MYNITA')].count"))
                .containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(body, "$.categories[?(@.provider == 'MYNITA')].totalCents"))
                .containsExactly(7_500);
        assertThat(JsonPath.<List<Integer>>read(body, "$.categories[?(@.provider == 'MYNITA')].rejectedCount"))
                .containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(body, "$.categories[?(@.provider == 'MYNITA')].rejectedCents"))
                .containsExactly(9_000);
        assertThat(JsonPath.<List<Integer>>read(body, "$.categories[?(@.provider == 'MYNITA')].pendingCents"))
                .containsExactly(7_500);
        assertThat(JsonPath.<List<Integer>>read(body, "$.categories[?(@.method == 'CASH')].totalCents"))
                .containsExactly(1_500);

        // --- the MyNITA transactions of the day, newest first, with station and cashier
        call(get(API + "/entries?from=" + today + "&method=MOBILE_MONEY&provider=MYNITA"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].reference").value("NITA-003"))
                .andExpect(jsonPath("$[0].confirmationStatus").value("REJECTED"))
                .andExpect(jsonPath("$[1].reference").value("NITA-002"))
                .andExpect(jsonPath("$[1].terminalId").value("CAISSE-2"))
                .andExpect(jsonPath("$[2].reference").value("NITA-001"))
                .andExpect(jsonPath("$[2].cashierName").isNotEmpty());

        // --- one station only
        call(get(API + "?from=" + today + "&terminalId=CAISSE-2"), owner, null)
                .andExpect(jsonPath("$.terminalId").value("CAISSE-2"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.totalCents").value(2_500 + 1_500))
                .andExpect(jsonPath("$.terminals.length()").value(2));
        call(get(API + "/entries?from=" + today + "&method=MOBILE_MONEY&provider=MYNITA&terminalId=CAISSE-2"),
                owner, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amountCents").value(2_500));

        // --- yesterday and the two days together
        call(get(API + "?from=" + today.minusDays(1)), owner, null)
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.totalCents").value(7_000));
        call(get(API + "?from=" + today.minusDays(1) + "&to=" + today), owner, null)
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.categories[?(@.provider == 'MYNITA')].totalCents").value(14_500));

        // --- a period backwards or too long is refused
        call(get(API + "?from=" + today + "&to=" + today.minusDays(1)), owner, null)
                .andExpect(status().isUnprocessableContent());
        call(get(API + "?from=" + today.minusDays(40) + "&to=" + today), owner, null)
                .andExpect(status().isUnprocessableContent());

        // --- another pharmacy sees nothing of it
        var other = onboardPharmacy("Encaissements Dosso");
        call(get(API + "?from=" + today), other.ownerToken(), null)
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.categories.length()").value(0))
                .andExpect(jsonPath("$.terminals.length()").value(0));
        call(get(API + "/entries?from=" + today), other.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static String payment(String method, long amountCents, String provider, String reference) {
        return provider == null
                ? "{\"method\":\"%s\",\"amountCents\":%d}".formatted(method, amountCents)
                : "{\"method\":\"%s\",\"amountCents\":%d,\"provider\":\"%s\",\"reference\":\"%s\"}"
                        .formatted(method, amountCents, provider, reference);
    }

    /** A sale of as many 500 F boxes as the payment settles exactly. */
    private String sale(String token, UUID product, String terminal, Instant occurredAt, String payment)
            throws Exception {
        long amount = ((Number) JsonPath.read(payment, "$.amountCents")).longValue();
        UUID id = UUID.randomUUID();
        return read(call(post("/api/v1/sales"), token, """
                {"clientSaleId":"%s","terminalId":"%s","ticketNumber":"%s-%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":500,"discountCents":0}],
                 "payments":[%s]}
                """.formatted(id, terminal, terminal, id.toString().substring(0, 6), occurredAt, product, amount / 500,
                payment)).andExpect(status().isCreated()), "$.saleId");
    }
}
