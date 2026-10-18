package com.pharmacore.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfflineSaleSyncIT extends IntegrationTest {

    @Test
    void offlineJournalReplayIsIdempotentAndConsumesLotsFefo() throws Exception {
        var pharmacy = onboardPharmacy("Fefo");
        var product = createProduct("IBUPROFENE 400MG CPR", "NONE", 300);
        UUID lateLot = receiveLot(pharmacy, product, "LATE", "2028-06-30", 10);
        UUID earlyLot = receiveLot(pharmacy, product, "EARLY", "2027-03-31", 2);
        UUID clientSaleId = UUID.randomUUID();
        String batch = batch(sale(clientSaleId, product, 3, 300, 0, 900, "\"" + minutesAgo(30) + "\""));

        var first = call(post("/api/v1/pos/sync/sales"), pharmacy.ownerToken(), batch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].type").value("ACCEPTED"))
                .andExpect(jsonPath("$.outcomes[0].clientSaleId").value(clientSaleId.toString()))
                .andExpect(jsonPath("$.outcomes[0].saleId").exists())
                .andExpect(jsonPath("$.outcomes[0].anomalies").isEmpty());
        String saleId = read(first, "$.outcomes[0].saleId");

        // The terminal lost the response and pushes the same journal again
        call(post("/api/v1/pos/sync/sales"), pharmacy.ownerToken(), batch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].type").value("DUPLICATE"))
                .andExpect(jsonPath("$.outcomes[0].saleId").value(saleId))
                .andExpect(jsonPath("$.outcomes[0].anomalies").doesNotExist());

        assertThat(available(pharmacy, product)).isEqualTo(9);
        assertThat(lotQuantity(pharmacy, product, "EARLY")).isZero();
        assertThat(lotQuantity(pharmacy, product, "LATE")).isEqualTo(9);
        assertThat(earlyLot).isNotEqualTo(lateLot);
    }

    @Test
    void offlineSaleBeyondStockIsKeptAndFlaggedButOnlineSaleIsRefused() throws Exception {
        var pharmacy = onboardPharmacy("Shortfall");
        var product = createProduct("SMECTA 3G PDR", "NONE", 450);
        receiveLot(pharmacy, product, "S-1", "2028-05-31", 1);

        call(post("/api/v1/pos/sync/sales"), pharmacy.ownerToken(),
                batch(sale(UUID.randomUUID(), product, 3, 450, 0, 1350, "\"" + minutesAgo(5) + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].anomalies[0]").value("STOCK_SHORTFALL"));

        call(get("/api/v1/sales/review-queue"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reviewRequired").value(true));
        call(get("/api/v1/inventory/stocks"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[0].shortfall").value(2))
                .andExpect(jsonPath("$[0].available").value(0));

        call(post("/api/v1/sales"), pharmacy.ownerToken(),
                sale(UUID.randomUUID(), product, 1, 450, 0, 450, "\"" + minutesAgo(0) + "\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("insufficient-stock"));
    }

    @Test
    void listedDrugGetsGapFreeOrdonnancierNumberAndCancellationRestoresTheLot() throws Exception {
        var pharmacy = onboardPharmacy("Ordonnancier");
        var antibiotic = createProduct("AMOXICILLINE 1G CPR", "LIST_I", 690);
        receiveLot(pharmacy, antibiotic, "AMX-1", "2028-02-28", 5);
        String prescription = """
                ,"prescription":{"prescriberName":"Dr Durand","prescriberRpps":"10003456789","patientReference":"PAT-9"}""";

        var first = call(post("/api/v1/sales"), pharmacy.ownerToken(),
                saleWith(UUID.randomUUID(), antibiotic, 1, 690, 690, prescription))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ordonnancierNumber").value(1));
        call(post("/api/v1/sales"), pharmacy.ownerToken(),
                saleWith(UUID.randomUUID(), antibiotic, 1, 690, 690, prescription))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ordonnancierNumber").value(2));

        // Online: a prescription-only drug without prescription is refused outright
        call(post("/api/v1/sales"), pharmacy.ownerToken(), saleWith(UUID.randomUUID(), antibiotic, 1, 690, 690, ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value("PRESCRIPTION_MISSING"));

        assertThat(available(pharmacy, antibiotic)).isEqualTo(3);
        call(post("/api/v1/sales/" + read(first, "$.saleId") + "/cancellation"), pharmacy.ownerToken(),
                "{\"reason\":\"Erreur de saisie\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(available(pharmacy, antibiotic)).isEqualTo(4);
    }

    private int lotQuantity(Pharmacy pharmacy, UUID product, String lot) throws Exception {
        var result = call(get("/api/v1/inventory/expiry-alerts?withinDays=730"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk());
        java.util.List<Integer> quantities = com.jayway.jsonpath.JsonPath.read(
                result.andReturn().getResponse().getContentAsString(),
                "$[?(@.productId == '" + product + "' && @.lotNumber == '" + lot + "')].quantity");
        return quantities.isEmpty() ? 0 : quantities.getFirst();
    }

    private static String minutesAgo(int minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).toString();
    }

    private static String batch(String... sales) {
        return "{\"sales\":[" + String.join(",", sales) + "]}";
    }

    private static String sale(UUID clientSaleId, UUID product, int quantity, long unitPrice, long discount, long paid,
                               String occurredAtJson) {
        return """
                {"clientSaleId":"%s","terminalId":"T01","ticketNumber":"T01-%s","occurredAt":%s,
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":%d,"discountCents":%d}],
                 "payments":[{"method":"CASH","amountCents":%d}]}
                """.formatted(clientSaleId, clientSaleId.toString().substring(0, 6), occurredAtJson, product, quantity,
                unitPrice, discount, paid);
    }

    private static String saleWith(UUID clientSaleId, UUID product, int quantity, long unitPrice, long paid,
                                   String prescriptionJson) {
        return """
                {"clientSaleId":"%s","terminalId":"T02","ticketNumber":"T02-%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":%d,"discountCents":0}],
                 "payments":[{"method":"THIRD_PARTY_PAYER","amountCents":%d,"thirdPartyCode":"AMO"}]%s}
                """.formatted(clientSaleId, clientSaleId.toString().substring(0, 6), Instant.now(), product, quantity,
                unitPrice, paid, prescriptionJson);
    }
}
