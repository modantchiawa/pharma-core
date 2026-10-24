package com.pharmacore.insurance;

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
 * Third-party payer flow: a mutual covering 80 %, a customer's policy, a split sale, the
 * statement sent to the insurer and its settlement, the receivable followed in the books.
 */
class InsuranceIT extends IntegrationTest {

    @Test
    void coveredSaleStatementAndSettlementHangTogether() throws Exception {
        var pharmacy = onboardPharmacy("Tiers Payant Niamey");
        var product = createProduct("METFORMINE 850MG CPR B/30", "NONE", 10_000);
        receiveLot(pharmacy, product, "M-1", "2028-01-31", 20);
        String customerId = read(call(post("/api/v1/customers"), pharmacy.ownerToken(), """
                {"displayName":"Aïchatou Garba","phone":"+22790111111","creditLimitCents":0}""")
                .andExpect(status().isCreated()), "$.id");
        String otherCustomer = read(call(post("/api/v1/customers"), pharmacy.ownerToken(), """
                {"displayName":"Autre Client","creditLimitCents":0}""")
                .andExpect(status().isCreated()), "$.id");

        // Insurer with its own receivable sub-account under 4112
        var insurer = call(post("/api/v1/insurers"), pharmacy.ownerToken(), """
                {"name":"Mutuelle des Enseignants","kind":"MUTUELLE","defaultCoverageRate":80,
                 "contactName":"M. Sani","phone":"+22720000000","paymentTermsDays":45}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("ASS001"))
                .andExpect(jsonPath("$.accountCode").value("4112001"));
        String insurerId = read(insurer, "$.id");
        String policyId = read(call(post("/api/v1/insurers/" + insurerId + "/policies"), pharmacy.ownerToken(), """
                {"customerId":"%s","policyNumber":"MEN-2026-0042","beneficiaryName":"Aïchatou Garba",
                 "coverageRate":80,"validFrom":"%s"}""".formatted(customerId, LocalDate.now().minusDays(10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usable").value(true)), "$.id");
        call(get("/api/v1/customers/" + customerId + "/policies"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].insurerName").value("Mutuelle des Enseignants"))
                .andExpect(jsonPath("$[0].coverageRate").value(80))
                .andExpect(jsonPath("$[0].remainingCeilingCents").doesNotExist());

        // 10 000 F sale: 8 000 covered by the mutual, 2 000 paid in cash
        String saleId = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, 10_000, customerId, """
                [{"method":"THIRD_PARTY_PAYER","amountCents":8000,"policyId":"%s"},{"method":"CASH","amountCents":2000}]
                """.formatted(policyId))).andExpect(status().isCreated()), "$.saleId");
        // A share that does not match the policy is refused online...
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, 10_000, customerId, """
                [{"method":"THIRD_PARTY_PAYER","amountCents":9000,"policyId":"%s"},{"method":"CASH","amountCents":1000}]
                """.formatted(policyId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value("INSURANCE_SHARE_MISMATCH"));
        // ...and so is another customer's policy
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, 10_000, otherCustomer, """
                [{"method":"THIRD_PARTY_PAYER","amountCents":8000,"policyId":"%s"},{"method":"CASH","amountCents":2000}]
                """.formatted(policyId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value("INSURANCE_POLICY_INVALID"));

        // Statement for the period: one line, 8 000, exported, issued
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate to = LocalDate.now().plusDays(1);
        var statement = call(post("/api/v1/insurers/" + insurerId + "/statements"), pharmacy.ownerToken(), """
                {"periodFrom":"%s","periodTo":"%s"}""".formatted(from, to))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.totalCents").value(8_000));
        String statementId = read(statement, "$.id");
        assertThat(read(statement, "$.number")).startsWith("BRD-" + LocalDate.now().getYear() + "-");
        call(post("/api/v1/insurers/" + insurerId + "/statements"), pharmacy.ownerToken(), """
                {"periodFrom":"%s","periodTo":"%s"}""".formatted(from, to))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("no-billable-payments"));
        call(get("/api/v1/insurance/statements/" + statementId), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].saleId").value(saleId))
                .andExpect(jsonPath("$.lines[0].beneficiaryName").value("Aïchatou Garba"))
                .andExpect(jsonPath("$.lines[0].amountCents").value(8_000));
        String csv = call(get("/api/v1/insurance/statements/" + statementId + "/export"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Bordereau;Assureur").contains("MEN-2026-0042;Aïchatou Garba;10000;8000")
                .contains("TOTAL;;;;;;;;;8000");
        call(post("/api/v1/insurance/statements/" + statementId + "/payment"), pharmacy.ownerToken(), """
                {"amountCents":8000,"method":"MOBILE_MONEY","provider":"ORANGE_MONEY"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("statement-not-issued"));
        call(post("/api/v1/insurance/statements/" + statementId + "/issue"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        call(get("/api/v1/insurance/receivables"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].insurerId").value(insurerId))
                .andExpect(jsonPath("$[0].billedUnpaidCents").value(8_000))
                .andExpect(jsonPath("$[0].unbilledCents").value(0));

        // Settled in two mobile money transfers; the receivable account is cleared
        call(post("/api/v1/insurance/statements/" + statementId + "/payment"), pharmacy.ownerToken(), """
                {"amountCents":3000,"method":"MOBILE_MONEY","provider":"ORANGE_MONEY","reference":"OM-1"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.paidCents").value(3_000));
        call(post("/api/v1/insurance/statements/" + statementId + "/payment"), pharmacy.ownerToken(), """
                {"amountCents":5000,"method":"CASH"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-method"));
        call(post("/api/v1/insurance/statements/" + statementId + "/payment"), pharmacy.ownerToken(), """
                {"amountCents":5000,"method":"MOBILE_MONEY","provider":"ORANGE_MONEY","reference":"OM-2"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidCents").value(8_000));
        var balance = call(get("/api/v1/accounting/trial-balance?from=" + from + "&to=" + to), pharmacy.ownerToken(), null)
                .andExpect(status().isOk());
        String balanceJson = balance.andReturn().getResponse().getContentAsString();
        List<Integer> insurerAccount = JsonPath.read(balanceJson, "$[?(@.account == '4112001')].balanceCents");
        assertThat(insurerAccount).containsExactly(0);
        List<Integer> insurerDebits = JsonPath.read(balanceJson, "$[?(@.account == '4112001')].debitCents");
        assertThat(insurerDebits).containsExactly(8_000);
        List<Integer> mobileMoney = JsonPath.read(balanceJson, "$[?(@.account == '5221')].balanceCents");
        assertThat(mobileMoney).containsExactly(8_000);
        List<Integer> genericThirdParty = JsonPath.read(balanceJson, "$[?(@.account == '4112')].balanceCents");
        assertThat(genericThirdParty).isEmpty();

        // Offline, a wrong share is kept for the pharmacist's review instead of being lost
        String offline = sale(product, 10_000, customerId, """
                [{"method":"THIRD_PARTY_PAYER","amountCents":9000,"policyId":"%s"},{"method":"CASH","amountCents":1000}]
                """.formatted(policyId));
        call(post("/api/v1/pos/sync/sales"), pharmacy.ownerToken(), "{\"sales\":[" + offline + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].type").value("ACCEPTED"))
                .andExpect(jsonPath("$.outcomes[0].anomalies[0]").value("INSURANCE_SHARE_MISMATCH"));
        call(get("/api/v1/sales/review-queue"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        call(get("/api/v1/insurance/receivables"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[0].billedUnpaidCents").value(0))
                .andExpect(jsonPath("$[0].unbilledCents").value(9_000))
                .andExpect(jsonPath("$[0].unbilledSales").value(1));
    }

    private static String sale(UUID product, long unitPrice, String customerId, String paymentsJson) {
        UUID id = UUID.randomUUID();
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",
                 "customerId":"%s",
                 "lines":[{"productId":"%s","quantity":1,"unitPriceCents":%d,"discountCents":0}],
                 "payments":%s}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(), customerId, product, unitPrice,
                paymentsJson.trim());
    }
}
