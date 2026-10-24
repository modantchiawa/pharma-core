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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A cashier without approval rights runs the drawer: blind count by denominations, declared
 * electronic totals reconciled per method, a difference above the tolerance signed by the
 * pharmacist, cancellations above the threshold or on a closed drawer signed with a
 * supervisor's PIN, a mobile money reference used twice refused, operator statement ticked.
 */
class CashReliabilityIT extends IntegrationTest {

    @Test
    void blindClosingApprovalsAndPaymentReconciliation() throws Exception {
        var pharmacy = onboardPharmacy("Caisse Maradi");
        var product = createProduct("AMOXICILLINE 1G CPR B/14", "NONE", 500);
        receiveLot(pharmacy, product, "A-1", "2028-01-31", 100);

        // Thresholds: 200 F tolerance on the drawer, 1 000 F for cancellations
        call(put("/api/v1/cash/policy"), pharmacy.ownerToken(), """
                {"cashDifferenceThresholdCents":200,"cancelApprovalThresholdCents":1000}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelApprovalThresholdCents").value(1000));

        // A cashier role that may cancel but not approve; a supervisor (ADJOINT) with a PIN
        String cashierRole = read(call(post("/api/v1/roles"), pharmacy.ownerToken(), """
                {"code":"VENDEUR","name":"Vendeur","maxDiscountPercent":2,
                 "permissions":["SALE_CREATE","SALE_VIEW","SALE_CANCEL","CASH_SESSION_MANAGE","PAYMENT_RECONCILE"]}""")
                .andExpect(status().isCreated()), "$.id");
        String cashier = staff(pharmacy, cashierRole, "vendeur").token();
        var supervisor = staff(pharmacy, roleId(pharmacy, "ADJOINT"), "adjoint");
        call(put("/api/v1/me/pin"), supervisor.token(), """
                {"password":"%s","pin":"2468"}""".formatted(supervisor.password()))
                .andExpect(status().isNoContent());

        // --- the day
        String session = read(call(post("/api/v1/cash/sessions"), cashier, """
                {"terminalId":"T-TEST","openingFloatCents":5000}""").andExpect(status().isCreated()), "$.sessionId");
        String s1 = sale(pharmacy, cashier, 2, "[{\"method\":\"CASH\",\"amountCents\":1000}]");
        String s2 = sale(pharmacy, cashier, 3, "[{\"method\":\"MOBILE_MONEY\",\"amountCents\":1500,\"provider\":\"AIRTEL_MONEY\",\"reference\":\"MM-001\"}]");
        String s3 = sale(pharmacy, cashier, 1, "[{\"method\":\"CARD\",\"amountCents\":500,\"reference\":\"CARD-1\"}]");
        String s4 = sale(pharmacy, cashier, 1, "[{\"method\":\"MOBILE_MONEY\",\"amountCents\":500,\"provider\":\"MOOV_MONEY\",\"reference\":\"MM-002\"}]");
        String s5 = sale(pharmacy, cashier, 3, "[{\"method\":\"CASH\",\"amountCents\":1500}]");
        // The same operator reference cannot justify a second payment
        call(post("/api/v1/sales"), pharmacy.ownerToken(), saleJson(product, 1,
                "[{\"method\":\"MOBILE_MONEY\",\"amountCents\":500,\"provider\":\"AIRTEL_MONEY\",\"reference\":\"MM-001\"}]"))
                .andExpect(status().isConflict());

        // --- cancellations: below threshold, drawer open: no signature needed
        call(post("/api/v1/sales/" + s4 + "/cancellation"), cashier, """
                {"reason":"Erreur de saisie","terminalId":"T-TEST"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // above threshold: the cashier needs a supervisor
        call(post("/api/v1/sales/" + s5 + "/cancellation"), cashier, """
                {"reason":"Client parti","terminalId":"T-TEST"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("approval-required"));
        call(post("/api/v1/sales/" + s5 + "/cancellation"), cashier, """
                {"reason":"Client parti","terminalId":"T-TEST","supervisorEmail":"%s","supervisorPin":"0000"}"""
                .formatted(supervisor.email()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("bad-credentials"));
        call(post("/api/v1/sales/" + s5 + "/cancellation"), cashier, """
                {"reason":"Client parti","terminalId":"T-TEST","supervisorEmail":"%s","supervisorPin":"2468"}"""
                .formatted(supervisor.email()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // --- blind X report: the cashier does not see the expected cash, the pharmacist does
        call(get("/api/v1/cash/sessions/" + session), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCashCents").doesNotExist())
                .andExpect(jsonPath("$.salesCount").value(3))
                .andExpect(jsonPath("$.cancelledCount").value(2));
        // 5 000 float + 1 000 + 1 500 cash received − 1 500 refunded = 6 000
        call(get("/api/v1/cash/sessions/" + session), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.expectedCashCents").value(6000))
                .andExpect(jsonPath("$.cashOutCents").value(1500));

        // --- closing by denominations: 5 000 + 500 + 2 × 100 = 5 700 → 300 F short, above the tolerance
        var closed = call(post("/api/v1/cash/sessions/" + session + "/closure"), cashier, """
                {"denominations":[{"value":5000,"count":1},{"value":500,"count":1},{"value":100,"count":2}],
                 "declared":[{"method":"MOBILE_MONEY","provider":"AIRTEL_MONEY","amountCents":1500},
                             {"method":"MOBILE_MONEY","provider":"MOOV_MONEY","amountCents":0},
                             {"method":"CARD","amountCents":400}],
                 "notes":"Manque"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.countedCashCents").value(5700))
                .andExpect(jsonPath("$.expectedCashCents").value(6000))
                .andExpect(jsonPath("$.differenceCents").value(-300))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.previousReportHash").value("GENESIS"))
                .andExpect(jsonPath("$.countedBreakdown.length()").value(3))
                .andExpect(jsonPath("$.reconciliation[?(@.method == 'CASH')].differenceCents").value(-300))
                .andExpect(jsonPath("$.reconciliation[?(@.method == 'CARD')].expectedCents").value(500))
                .andExpect(jsonPath("$.reconciliation[?(@.method == 'CARD')].differenceCents").value(-100))
                .andExpect(jsonPath("$.reconciliation[?(@.provider == 'AIRTEL_MONEY')].differenceCents").value(0));
        String hash = read(closed, "$.reportHash");
        assertThat(hash).hasSize(64);
        call(post("/api/v1/cash/sessions/" + session + "/approval"), cashier, "{}")
                .andExpect(status().isForbidden());
        call(get("/api/v1/cash/sessions/pending-approval"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[0].sessionId").value(session));
        call(post("/api/v1/cash/sessions/" + session + "/approval"), pharmacy.ownerToken(), """
                {"note":"Vu avec le vendeur"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
        call(get("/api/v1/cash/sessions/pending-approval"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));

        // the next Z report of the terminal chains on this one
        String session2 = read(call(post("/api/v1/cash/sessions"), cashier, """
                {"terminalId":"T-TEST","openingFloatCents":5700}""").andExpect(status().isCreated()), "$.sessionId");
        call(post("/api/v1/cash/sessions/" + session2 + "/closure"), cashier, """
                {"countedCashCents":5700}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.differenceCents").value(0))
                .andExpect(jsonPath("$.approvalStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.previousReportHash").value(hash));

        // --- a sale of a closed drawer can only be cancelled with a signature, whatever the amount
        call(post("/api/v1/sales/" + s1 + "/cancellation"), cashier, """
                {"reason":"Retour","terminalId":"T-TEST"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("approval-required"));
        call(post("/api/v1/sales/" + s1 + "/cancellation"), cashier, """
                {"reason":"Retour","terminalId":"T-TEST","supervisorEmail":"%s","supervisorPin":"2468"}"""
                .formatted(supervisor.email()))
                .andExpect(status().isOk());
        // the pharmacist approves alone
        call(post("/api/v1/sales/" + s3 + "/cancellation"), pharmacy.ownerToken(), """
                {"reason":"Retour","terminalId":"T-TEST"}""")
                .andExpect(status().isOk());

        // --- operator statement: pending electronic payments ticked or rejected
        var pending = call(get("/api/v1/payments/pending"), cashier, null).andExpect(status().isOk());
        String pendingJson = pending.andReturn().getResponse().getContentAsString();
        List<String> refs = JsonPath.read(pendingJson, "$[*].reference");
        assertThat(refs).containsExactlyInAnyOrder("MM-001");
        String payment2 = JsonPath.<List<String>>read(pendingJson, "$[?(@.saleId == '" + s2 + "')].paymentId").getFirst();
        call(post("/api/v1/payments/" + payment2 + "/rejection"), cashier, """
                {"reason":"Absent du relevé Airtel du 24/10"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        call(get("/api/v1/sales/" + s2), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.summary.reviewRequired").value(true))
                .andExpect(jsonPath("$.payments[0].confirmationStatus").value("REJECTED"));
        call(post("/api/v1/payments/" + payment2 + "/confirmation"), cashier, null)
                .andExpect(status().isUnprocessableContent());
        call(get("/api/v1/payments/pending"), cashier, null)
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/payments/pending?status=REJECTED"), cashier, null)
                .andExpect(jsonPath("$[0].reference").value("MM-001"));
    }

    private record Staff(String email, String password, String token) {
    }

    private Staff staff(Pharmacy pharmacy, String roleId, String name) throws Exception {
        String email = name + "-" + pharmacy.slug() + "@example.org";
        var registered = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"%s","roleIds":["%s"]}""".formatted(email, name, roleId))
                .andExpect(status().isCreated());
        String password = read(registered, "$.temporaryPassword");
        return new Staff(email, password, login(pharmacy.slug(), email, password));
    }

    private String roleId(Pharmacy pharmacy, String code) throws Exception {
        var roles = call(get("/api/v1/roles"), pharmacy.ownerToken(), null).andExpect(status().isOk());
        return JsonPath.<List<String>>read(roles.andReturn().getResponse().getContentAsString(),
                "$[?(@.code == '" + code + "')].id").getFirst();
    }

    private String sale(Pharmacy pharmacy, String token, int quantity, String payments) throws Exception {
        var product = JsonPath.read(call(get("/api/v1/inventory/stocks"), pharmacy.ownerToken(), null)
                .andReturn().getResponse().getContentAsString(), "$[0].productId").toString();
        return read(call(post("/api/v1/sales"), token, saleJson(UUID.fromString(product), quantity, payments))
                .andExpect(status().isCreated()), "$.saleId");
    }

    private static String saleJson(UUID product, int quantity, String payments) {
        UUID id = UUID.randomUUID();
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":500,"discountCents":0}],
                 "payments":%s}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(), product, quantity, payments);
    }
}
