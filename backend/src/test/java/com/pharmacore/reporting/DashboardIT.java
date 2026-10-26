package com.pharmacore.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.catalog.domain.Cip13;
import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/** Management dashboard and therapeutic substitutes over a short counter day. */
class DashboardIT extends IntegrationTest {

    private static final long LOT_COST = 120;

    @Test
    void dashboardAggregatesSalesMarginStockAndAlternativesFollowTheDci() throws Exception {
        var pharmacy = onboardPharmacy("Pilotage");
        UUID brand = product("DOLIPRANE 500MG CPR", "PARACETAMOL", "500MG", 1_000);
        UUID generic = product("PARACETAMOL BIOGARAN 500MG CPR", "PARACETAMOL", "500MG", 600);
        UUID strong = product("PARACETAMOL 1000MG CPR", "PARACETAMOL", "1000MG", 900);
        UUID other = product("AMOXICILLINE 1G CPR", "AMOXICILLINE", "1G", 2_000);
        receiveLot(pharmacy, generic, "G-1", "2028-06-30", 50);
        receiveLot(pharmacy, other, "A-1", LocalDate.now().plusDays(60).toString(), 10);

        // Two sales today: 3 generics (cash) and 2 amoxicillin (mobile money), then one cancelled sale
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(generic, 3, 600, """
                [{"method":"CASH","amountCents":1800}]""")).andExpect(status().isCreated());
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(other, 2, 2_000, """
                [{"method":"MOBILE_MONEY","amountCents":4000,"provider":"ZAMANI_CASH"}]""")).andExpect(status().isCreated());
        String cancelled = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(generic, 1, 600, """
                [{"method":"CASH","amountCents":600}]""")).andExpect(status().isCreated()), "$.saleId");
        call(post("/api/v1/sales/" + cancelled + "/cancellation"), pharmacy.ownerToken(), """
                {"reason":"Erreur de saisie","terminalId":"T-TEST"}""").andExpect(status().isOk());

        LocalDate today = LocalDate.now();
        var result = call(get("/api/v1/dashboard?from=" + today.minusDays(6) + "&to=" + today), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sales.count").value(2))
                .andExpect(jsonPath("$.sales.cancelledCount").value(1))
                .andExpect(jsonPath("$.sales.previousCount").value(0))
                .andExpect(jsonPath("$.receivables.debtors").value(0))
                .andExpect(jsonPath("$.stock.expiringLots").value(1))
                .andExpect(jsonPath("$.cash.sessionsClosed").value(0));
        String body = result.andReturn().getResponse().getContentAsString();
        long net = ((Number) JsonPath.read(body, "$.sales.netCents")).longValue();
        long margin = ((Number) JsonPath.read(body, "$.sales.marginCents")).longValue();
        long average = ((Number) JsonPath.read(body, "$.sales.averageBasketCents")).longValue();
        assertThat(net).isEqualTo(1_800 + 4_000);
        assertThat(margin).isEqualTo(net - (3 + 2) * LOT_COST);
        assertThat(average).isEqualTo(net / 2);
        List<String> days = JsonPath.read(body, "$.daily[*].date");
        assertThat(days).hasSize(1);
        List<String> methods = JsonPath.read(body, "$.payments[*].method");
        assertThat(methods).containsExactly("MOBILE_MONEY", "CASH");
        assertThat((String) JsonPath.read(body, "$.payments[0].provider")).isEqualTo("ZAMANI_CASH");
        assertThat((String) JsonPath.read(body, "$.topProducts[0].designation")).isEqualTo("AMOXICILLINE 1G CPR");
        long stockValue = ((Number) JsonPath.read(body, "$.stock.valueAtCostCents")).longValue();
        assertThat(stockValue).isEqualTo((50 - 3) * LOT_COST + (10 - 2) * LOT_COST);
        long expiringValue = ((Number) JsonPath.read(body, "$.stock.expiringWithin90DaysCents")).longValue();
        assertThat(expiringValue).isEqualTo((10 - 2) * LOT_COST);

        // a period longer than a year is refused, a cashier without the permission too
        call(get("/api/v1/dashboard?from=" + today.minusYears(2) + "&to=" + today), pharmacy.ownerToken(), null)
                .andExpect(status().isUnprocessableContent());

        // Substitutes: the brand is out of stock; the generic with the same DCI and dosage comes first,
        // the 1000 mg strength is not an equivalent, another molecule has none
        call(get("/api/v1/catalog/products/" + brand + "/alternatives"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(generic.toString()))
                .andExpect(jsonPath("$[0].availableQuantity").value(47))
                .andExpect(jsonPath("$[0].unitPriceCents").value(600));
        call(get("/api/v1/catalog/products/" + strong + "/alternatives"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/catalog/products/" + other + "/alternatives"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private UUID product(String designation, String dci, String dosage, long priceCents) throws Exception {
        String first12 = "34009" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        String cip13 = first12 + Cip13.checkDigit(first12);
        var result = call(put("/api/platform/catalog/products/" + cip13), platformToken, """
                {"designation":"%s","dci":"%s","dosage":"%s","listing":"NONE","vatRate":2.10,"publicPriceCents":%d,"active":true}
                """.formatted(designation, dci, dosage, priceCents))
                .andExpect(status().isOk());
        return UUID.fromString(read(result, "$.id"));
    }

    private static String sale(UUID product, int quantity, long unitPrice, String paymentsJson) {
        UUID id = UUID.randomUUID();
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":%d,"discountCents":0}],
                 "payments":%s}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(), product, quantity, unitPrice,
                paymentsJson.trim());
    }
}
