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

/**
 * A day at the counter in Niamey: cash drawer opened, sales paid in cash, mobile money and
 * on credit, a credit settled, the drawer closed with a shortage, the books and the next-day
 * order sheet derived from it, a delivery received. Amounts in XOF (no decimals).
 */
class CounterDayIT extends IntegrationTest {

    @Test
    void cashDrawerCreditAccountingAndOrderSheetHangTogether() throws Exception {
        var pharmacy = onboardPharmacy("Comptoir Niamey");
        var paracetamol = createProduct("PARACETAMOL 500MG CPR B/20", "NONE", 500);
        var amoxicillin = createProduct("AMOXICILLINE 500MG GEL B/12", "LIST_I", 1_800);
        receiveLot(pharmacy, paracetamol, "P-1", "2028-01-31", 30);
        receiveLot(pharmacy, amoxicillin, "A-1", "2027-12-31", 10);

        // Supplier and delivery through procurement: stock in, purchase booked (HT 12 000 + VAT 0)
        String supplierId = read(call(post("/api/v1/procurement/suppliers"), pharmacy.ownerToken(), """
                {"name":"Laborex Niger","leadTimeDays":2,"minOrderCents":0}""")
                .andExpect(status().isCreated()), "$.id");
        call(post("/api/v1/procurement/receipts"), pharmacy.ownerToken(), """
                {"supplierId":"%s","supplierInvoiceNumber":"F-2026-1001","invoiceDate":"%s",
                 "lines":[{"productId":"%s","lotNumber":"P-2","expiryDate":"2028-06-30","quantity":40,
                           "unitCostCents":300,"vatRate":0,"sellingPriceCents":550}]}
                """.formatted(supplierId, LocalDate.now(), paracetamol))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalExclVatCents").value(12_000));

        // Customer with a credit line
        String customerId = read(call(post("/api/v1/customers"), pharmacy.ownerToken(), """
                {"displayName":"Moussa Issoufou","phone":"+22796000000","creditLimitCents":5000}""")
                .andExpect(status().isCreated()), "$.id");

        // Drawer opened with a 10 000 F float
        call(post("/api/v1/cash/sessions"), pharmacy.ownerToken(), """
                {"terminalId":"T-TEST","openingFloatCents":10000}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
        call(post("/api/v1/cash/sessions"), pharmacy.ownerToken(), """
                {"terminalId":"T-TEST","openingFloatCents":10000}""")
                .andExpect(status().isUnprocessableContent());

        // Sale 1: 2 paracetamol at the new price (1 100), half cash half Airtel Money
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(paracetamol, 2, 550, """
                [{"method":"CASH","amountCents":600},{"method":"MOBILE_MONEY","amountCents":500,"provider":"AIRTEL_MONEY"}]
                """, null, null)).andExpect(status().isCreated());
        // Sale 2: antibiotic on prescription, fully on the customer's credit
        String creditSale = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(amoxicillin, 1, 1_800, """
                [{"method":"CREDIT","amountCents":1800}]""", customerId,
                "\"prescription\":{\"prescriberName\":\"Dr Maïga\",\"prescriberRpps\":\"10000000001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ordonnancierNumber").value(1)), "$.saleId");
        // Credit beyond the limit is refused online
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(amoxicillin, 2, 1_800, """
                [{"method":"CREDIT","amountCents":3600}]""", customerId,
                "\"prescription\":{\"prescriberName\":\"Dr Maïga\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value("CREDIT_LIMIT_EXCEEDED"));
        call(get("/api/v1/customers/" + customerId), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.creditBalanceCents").value(1_800))
                .andExpect(jsonPath("$.availableCreditCents").value(3_200));

        // The customer settles 800 F in cash at the counter
        call(post("/api/v1/customers/" + customerId + "/payments"), pharmacy.ownerToken(), """
                {"amountCents":800,"method":"CASH","terminalId":"T-TEST"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingBalanceCents").value(1_000));

        // An expense paid from the drawer
        var open = call(get("/api/v1/cash/sessions/open?terminalId=T-TEST"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk());
        String sessionId = read(open, "$.sessionId");
        call(post("/api/v1/cash/sessions/" + sessionId + "/movements"), pharmacy.ownerToken(), """
                {"kind":"EXPENSE","amountCents":1500,"reason":"Carburant groupe électrogène","expenseAccount":"6052"}""")
                .andExpect(status().isCreated());

        // X report before closing: 10 000 + 600 cash + 800 settlement − 1 500 expense = 9 900 expected
        call(get("/api/v1/cash/sessions/" + sessionId), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.salesCount").value(2))
                .andExpect(jsonPath("$.byPaymentMethod.CASH").value(600))
                .andExpect(jsonPath("$.byPaymentMethod.MOBILE_MONEY").value(500))
                .andExpect(jsonPath("$.byPaymentMethod.CREDIT").value(1_800))
                .andExpect(jsonPath("$.mobileMoneyByProvider.AIRTEL_MONEY").value(500))
                .andExpect(jsonPath("$.expectedCashCents").value(9_900));
        // Closed with 9 800 counted: a 100 F shortage, booked as an expense
        call(post("/api/v1/cash/sessions/" + sessionId + "/closure"), pharmacy.ownerToken(), """
                {"countedCashCents":9800,"notes":"Manque 100 F"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.differenceCents").value(-100));

        // Books: the trial balance is balanced and the income statement reflects the day
        var balance = call(get("/api/v1/accounting/trial-balance?from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now()),
                pharmacy.ownerToken(), null).andExpect(status().isOk());
        String balanceJson = balance.andReturn().getResponse().getContentAsString();
        List<Integer> debits = JsonPath.read(balanceJson, "$[*].debitCents");
        List<Integer> credits = JsonPath.read(balanceJson, "$[*].creditCents");
        assertThat(debits.stream().mapToLong(Integer::longValue).sum())
                .isEqualTo(credits.stream().mapToLong(Integer::longValue).sum());
        List<Integer> cashBalance = JsonPath.read(balanceJson, "$[?(@.account == '571')].balanceCents");
        assertThat(cashBalance.getFirst()).isEqualTo(600 + 800 - 1_500 - 100);
        List<Integer> customersBalance = JsonPath.read(balanceJson, "$[?(@.account == '4111')].balanceCents");
        assertThat(customersBalance.getFirst()).isEqualTo(1_000);
        List<Integer> suppliersBalance = JsonPath.read(balanceJson, "$[?(@.account == '401')].balanceCents");
        assertThat(suppliersBalance.getFirst()).isEqualTo(-12_000);

        call(get("/api/v1/accounting/income-statement?from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now()),
                pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                // products carry 2.10 % VAT in the fixture: 1 100 TTC = 1 077 HT + 23, 1 800 TTC = 1 763 HT + 37
                .andExpect(jsonPath("$.salesCents").value(1_077 + 1_763))
                .andExpect(jsonPath("$.purchasesCents").value(12_000))
                .andExpect(jsonPath("$.otherExpensesCents").value(1_500 + 100));
        call(get("/api/v1/accounting/balance-sheet?asOf=" + LocalDate.now()), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerReceivablesCents").value(1_000))
                .andExpect(jsonPath("$.suppliersCents").value(12_000))
                .andExpect(jsonPath("$.mobileMoneyCents").value(500));

        // Cancelling the credit sale releases the credit and reverses the entry
        call(post("/api/v1/sales/" + creditSale + "/cancellation"), pharmacy.ownerToken(), """
                {"reason":"Erreur","terminalId":"T-TEST"}""")
                .andExpect(status().isOk());
        call(get("/api/v1/customers/" + customerId), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.creditBalanceCents").value(-800));

        // Next-day order sheet: paracetamol sells 2/day over the window → coverage → not needed yet
        // (68 on hand); lower the threshold rule: set a minimum of 80 to force a proposal line.
        call(put("/api/v1/inventory/stocks/" + paracetamol + "/settings"), pharmacy.ownerToken(), """
                {"sellingPriceCents":550,"reorderThreshold":80,"reorderQuantity":60,"location":"A1"}""")
                .andExpect(status().isNoContent());
        call(put("/api/v1/procurement/suppliers/" + supplierId + "/products/" + paracetamol), pharmacy.ownerToken(), """
                {"supplierReference":"LBX-001","purchasePriceCents":300,"packSize":10,"preferred":true}""")
                .andExpect(status().isNoContent());
        var proposal = call(get("/api/v1/procurement/proposal?supplierId=" + supplierId), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].productId").value(paracetamol.toString()))
                .andExpect(jsonPath("$.lines[0].suggestedQuantity").value(60))
                .andExpect(jsonPath("$.lines[0].reasons[0]").value("BELOW_THRESHOLD"));
        assertThat(read(proposal, "$.estimatedTotalCents")).isEqualTo("18000");

        String orderId = read(call(post("/api/v1/procurement/orders"), pharmacy.ownerToken(), """
                {"supplierId":"%s","lines":[{"productId":"%s","quantity":60,"suggestedQuantity":60,"reason":"seuil"}]}
                """.formatted(supplierId, paracetamol)).andExpect(status().isCreated()), "$.id");
        call(post("/api/v1/procurement/orders/" + orderId + "/send"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
        String text = call(get("/api/v1/procurement/orders/" + orderId + "/text"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(text).contains("60 x PARACETAMOL 500MG CPR B/20").contains("Laborex Niger");
        // Receiving the delivery closes the order and raises the stock
        call(post("/api/v1/procurement/receipts"), pharmacy.ownerToken(), """
                {"supplierId":"%s","purchaseOrderId":"%s","supplierInvoiceNumber":"F-2026-1002",
                 "lines":[{"productId":"%s","lotNumber":"P-3","expiryDate":"2028-09-30","quantity":60,"unitCostCents":300,"vatRate":0}]}
                """.formatted(supplierId, orderId, paracetamol))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("RECEIVED"));
        assertThat(available(pharmacy, paracetamol)).isEqualTo(30 + 40 - 2 + 60);
    }

    private static String sale(UUID product, int quantity, long unitPrice, String paymentsJson, String customerId,
                               String extra) {
        UUID id = UUID.randomUUID();
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",
                 %s
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":%d,"discountCents":0}],
                 "payments":%s%s}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(),
                customerId == null ? "" : "\"customerId\":\"" + customerId + "\",",
                product, quantity, unitPrice, paymentsJson.trim(), extra == null ? "" : "," + extra);
    }
}
