package com.pharmacore.prescription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Listed products land in the dispensing register with their ordonnancier number; a chronic
 * treatment's prescription on file is consumed dispensing by dispensing until exhausted.
 */
class PrescriptionIT extends IntegrationTest {

    @Test
    void registerAndRenewablePrescriptions() throws Exception {
        var pharmacy = onboardPharmacy("Ordonnancier Zinder");
        var paracetamol = createProduct("PARACETAMOL 1G CPR B/8", "NONE", 500);
        var amoxicillin = createProduct("AMOXICILLINE 1G CPR B/14", "LIST_I", 1_800);
        var morphine = createProduct("MORPHINE 10MG CPR B/14", "STUPEFIANT", 2_000);
        receiveLot(pharmacy, paracetamol, "P-1", "2028-01-31", 50);
        receiveLot(pharmacy, amoxicillin, "A-1", "2028-01-31", 50);
        receiveLot(pharmacy, morphine, "S-1", "2028-01-31", 50);
        String customerId = read(call(post("/api/v1/customers"), pharmacy.ownerToken(), """
                {"displayName":"Hassane Boubacar","phone":"+22796222222","creditLimitCents":0}""")
                .andExpect(status().isCreated()), "$.id");

        // --- register: one prescription sale with two listed products and an OTC one
        String prescriber = "\"prescription\":{\"prescriberName\":\"Dr Abdou\",\"prescriberRpps\":\"10000000002\",\"patientReference\":\"HB-1\"}";
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(List.of(line(amoxicillin, 1, 1_800), line(morphine, 1, 2_000),
                line(paracetamol, 2, 500)), 4_800, null, prescriber))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ordonnancierNumber").value(1));
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate to = LocalDate.now().plusDays(1);
        call(get("/api/v1/prescriptions/register?from=" + from + "&to=" + to), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].ordonnancierNumber").value(1))
                .andExpect(jsonPath("$[0].prescriberName").value("Dr Abdou"))
                .andExpect(jsonPath("$[0].patientReference").value("HB-1"));
        call(get("/api/v1/prescriptions/register?from=" + from + "&to=" + to + "&listing=STUPEFIANT"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].designation").value("MORPHINE 10MG CPR B/14"))
                .andExpect(jsonPath("$[0].listing").value("STUPEFIANT"))
                .andExpect(jsonPath("$[0].cashier").value("Dr Ordonnancier Zinder"));
        String csv = call(get("/api/v1/prescriptions/register/export?from=" + from + "&to=" + to + "&listing=STUPEFIANT"),
                pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Numero ordonnancier;Date;Ticket").contains("MORPHINE 10MG CPR B/14;STUPEFIANT;1")
                .doesNotContain("AMOXICILLINE");

        // --- renewable prescription: 2 dispensings allowed, the third is refused
        String prescriptionId = read(call(post("/api/v1/prescriptions"), pharmacy.ownerToken(), """
                {"customerId":"%s","prescriberName":"Dr Abdou","prescriberIdentifier":"NE-4521","issuedOn":"%s",
                 "validUntil":"%s","renewalsAllowed":2,"notes":"Traitement chronique",
                 "lines":[{"productId":"%s","quantity":1,"posology":"1 comprimé matin et soir"}]}
                """.formatted(customerId, LocalDate.now(), LocalDate.now().plusDays(90), amoxicillin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.renewalsRemaining").value(2))
                .andExpect(jsonPath("$.lines[0].designation").value("AMOXICILLINE 1G CPR B/14")), "$.id");
        String onFile = "\"prescription\":{\"prescriberName\":\"Dr Abdou\",\"prescriptionId\":\"" + prescriptionId + "\"}";
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(List.of(line(amoxicillin, 1, 1_800)), 1_800, customerId, onFile))
                .andExpect(status().isCreated());
        call(get("/api/v1/prescriptions/" + prescriptionId), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.renewalsUsed").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lastDispensedAt").isNotEmpty());
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(List.of(line(amoxicillin, 1, 1_800)), 1_800, customerId, onFile))
                .andExpect(status().isCreated());
        call(get("/api/v1/prescriptions/" + prescriptionId), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.renewalsUsed").value(2))
                .andExpect(jsonPath("$.renewalsRemaining").value(0))
                .andExpect(jsonPath("$.status").value("EXHAUSTED"));
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(List.of(line(amoxicillin, 1, 1_800)), 1_800, customerId, onFile))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value("PRESCRIPTION_RENEWAL_REFUSED"));
        call(get("/api/v1/prescriptions?customerId=" + customerId), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].customerName").value("Hassane Boubacar"));

        // --- a sale recorded without the file can be attached afterwards, once
        String single = read(call(post("/api/v1/prescriptions"), pharmacy.ownerToken(), """
                {"customerId":"%s","prescriberName":"Dr Abdou","issuedOn":"%s","validUntil":"%s","renewalsAllowed":1,
                 "lines":[{"productId":"%s","quantity":1}]}
                """.formatted(customerId, LocalDate.now(), LocalDate.now().plusDays(30), morphine))
                .andExpect(status().isCreated()), "$.id");
        String saleId = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(List.of(line(morphine, 1, 2_000)), 2_000,
                customerId, prescriber)).andExpect(status().isCreated()), "$.saleId");
        call(post("/api/v1/prescriptions/" + single + "/dispensing"), pharmacy.ownerToken(), "{\"saleId\":\"" + saleId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXHAUSTED"));
        call(post("/api/v1/prescriptions/" + single + "/dispensing"), pharmacy.ownerToken(), "{\"saleId\":\"" + saleId + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("sale-already-attached"));

        // --- renewals due: issued 40 days ago, dispensings remain, nothing dispensed yet
        String due = read(call(post("/api/v1/prescriptions"), pharmacy.ownerToken(), """
                {"customerId":"%s","prescriberName":"Dr Abdou","issuedOn":"%s","validUntil":"%s","renewalsAllowed":3,
                 "lines":[{"productId":"%s","quantity":2}]}
                """.formatted(customerId, LocalDate.now().minusDays(40), LocalDate.now().plusDays(50), paracetamol))
                .andExpect(status().isCreated()), "$.id");
        var dueList = call(get("/api/v1/prescriptions/renewals-due?withinDays=7"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk());
        List<String> dueIds = JsonPath.read(dueList.andReturn().getResponse().getContentAsString(), "$[*].id");
        assertThat(dueIds).contains(due).doesNotContain(prescriptionId, single);
        call(post("/api/v1/prescriptions/" + due + "/cancellation"), pharmacy.ownerToken(), "{\"reason\":\"Traitement arrêté\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    private static String line(UUID product, int quantity, long unitPrice) {
        return "{\"productId\":\"%s\",\"quantity\":%d,\"unitPriceCents\":%d,\"discountCents\":0}"
                .formatted(product, quantity, unitPrice);
    }

    private static String sale(List<String> lines, long total, String customerId, String extra) {
        UUID id = UUID.randomUUID();
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",
                 %s
                 "lines":[%s],
                 "payments":[{"method":"CASH","amountCents":%d}],%s}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(),
                customerId == null ? "" : "\"customerId\":\"" + customerId + "\",",
                String.join(",", lines), total, extra);
    }
}
