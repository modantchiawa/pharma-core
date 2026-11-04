package com.pharmacore.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.importing.domain.XlsxReader;
import com.pharmacore.importing.domain.XlsxWriter;
import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The counter rules of V25: no ticket without an open drawer, a cashier who neither opens nor
 * closes it and sees only their own sales and receipts, the session archived at the closing,
 * bons and special orders served by the next delivery, the order sheet and invoices in Excel,
 * the mobile money balance read from the operator's SMS.
 */
class CounterDisciplineIT extends IntegrationTest {

    @Test
    void noTicketWithoutAnOpenDrawerAndTheCashierSeesOnlyTheirOwnSales() throws Exception {
        var pharmacy = onboardPharmacy("Discipline Zinder");
        requireCashSession(pharmacy.tenantId(), true);
        var product = createProduct("PARACETAMOL 500MG CPR B/20", "NONE", 500);
        receiveLot(pharmacy, product, "P-1", "2028-01-31", 50);
        String cashier = staff(pharmacy, roleId(pharmacy, "CAISSIER"), "caissier");

        // Drawer closed: the live sale is refused, an offline replay is kept for review
        call(post("/api/v1/sales"), cashier, sale(product, 1, null))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value("NO_CASH_SESSION"));
        call(post("/api/v1/pos/sync/sales"), cashier, "{\"sales\":[" + sale(product, 1, null) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].anomalies[0]").value("NO_CASH_SESSION"));

        // The cashier neither opens nor closes the drawer by default
        call(post("/api/v1/cash/sessions"), cashier, """
                {"terminalId":"T-TEST","openingFloatCents":5000}""")
                .andExpect(status().isForbidden());
        String session = read(call(post("/api/v1/cash/sessions"), pharmacy.ownerToken(), """
                {"terminalId":"T-TEST","openingFloatCents":5000}""")
                .andExpect(status().isCreated()), "$.sessionId");

        String own = read(call(post("/api/v1/sales"), cashier, sale(product, 2, null))
                .andExpect(status().isCreated()), "$.saleId");
        String owner = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, 1, null))
                .andExpect(status().isCreated()), "$.saleId");

        // Their own sales only, whatever they ask; the pharmacist sees everyone
        String mine = body(call(get("/api/v1/sales"), cashier, null).andExpect(status().isOk()));
        List<String> mineIds = JsonPath.read(mine, "$[*].id");
        assertThat(mineIds).contains(own).doesNotContain(owner);
        call(get("/api/v1/sales/" + owner), cashier, null).andExpect(status().isNotFound());
        List<String> all = JsonPath.read(body(call(get("/api/v1/sales"), pharmacy.ownerToken(), null)), "$[*].id");
        assertThat(all).contains(own, owner);
        List<String> ownerOnly = JsonPath.read(body(call(get("/api/v1/sales?mine=true"), pharmacy.ownerToken(), null)),
                "$[*].id");
        assertThat(ownerOnly).contains(owner).doesNotContain(own);

        // Receipts: the cashier's journal holds what they cashed (the replayed sale and the live
        // one, 1 500 F), not the owner's 500 F
        call(get("/api/v1/payments/journal"), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCents").value(1_500));
        call(get("/api/v1/payments/journal"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.totalCents").value(2_000));

        // Closing freezes every operation of the session with its fingerprint
        call(post("/api/v1/cash/sessions/" + session + "/closure"), cashier, """
                {"countedCashCents":6500}""").andExpect(status().isForbidden());
        call(post("/api/v1/cash/sessions/" + session + "/closure"), pharmacy.ownerToken(), """
                {"countedCashCents":6500}""").andExpect(status().isOk());
        call(get("/api/v1/cash/sessions/" + session + "/archive"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.archive.salesCount").value(2))
                .andExpect(jsonPath("$.archive.sales[0].payments[0].method").value("CASH"));
        byte[] workbook = call(get("/api/v1/cash/sessions/" + session + "/archive/xlsx"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(XlsxReader.isXlsx(workbook)).isTrue();
    }

    @Test
    void bonsAndSpecialOrdersAreServedByTheNextDelivery() throws Exception {
        var pharmacy = onboardPharmacy("Bons Maradi");
        var scarce = createProduct("AMOXICILLINE 1G CPR B/14", "NONE", 1_000);
        var rare = createProduct("SERUM PHYSIOLOGIQUE 5ML B/40", "NONE", 2_000);
        receiveLot(pharmacy, scarce, "A-1", "2028-01-31", 1);

        // 3 boxes sold and paid, 1 on the shelf: 2 owed on a bon, which needs a way to reach the customer
        String noContact = """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-B0","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":3,"unitPriceCents":1000,"discountCents":0,"deferredQuantity":2}],
                 "payments":[{"method":"CASH","amountCents":3000}]}""".formatted(UUID.randomUUID(), Instant.now(), scarce);
        call(post("/api/v1/sales"), pharmacy.ownerToken(), noContact)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0]").value("VOUCHER_HOLDER_MISSING"));
        String bonSale = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-B1","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":3,"unitPriceCents":1000,"discountCents":0,"deferredQuantity":2}],
                 "payments":[{"method":"CASH","amountCents":3000}],
                 "voucherHolder":{"name":"Ibrahim","phone":"+22796000001"}}"""
                .formatted(UUID.randomUUID(), Instant.now(), scarce))
                .andExpect(status().isCreated()), "$.saleId");
        assertThat(available(pharmacy, scarce)).isZero();

        // A special order for a product never stocked
        String special = read(call(post("/api/v1/customer-orders"), pharmacy.ownerToken(), """
                {"holderName":"Awa Soumana","holderPhone":"+22790112233","productId":"%s","quantity":2}"""
                .formatted(rare))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN")), "$.id");
        String bons = body(call(get("/api/v1/customer-orders?pending=true"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk()));
        String voucher = JsonPath.<List<String>>read(bons, "$[?(@.kind == 'VOUCHER')].id").getFirst();
        assertThat(JsonPath.<List<String>>read(bons, "$[?(@.kind == 'VOUCHER')].saleId")).containsExactly(bonSale);

        // The order sheet asks for what the customers wait for
        String sheet = body(call(get("/api/v1/procurement/proposal"), pharmacy.ownerToken(), null));
        List<List<String>> reasons = JsonPath.read(sheet, "$.lines[?(@.productId == '" + rare + "')].reasons");
        assertThat(reasons.getFirst()).contains("CUSTOMER_ORDER");

        // The delivery reserves the units for the waiting customers: no longer sold to others
        receiveLot(pharmacy, scarce, "A-2", "2028-03-31", 2);
        receiveLot(pharmacy, rare, "S-1", "2028-03-31", 5);
        call(get("/api/v1/customer-orders/" + voucher), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.status").value("READY"));
        call(get("/api/v1/customer-orders/" + special), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.status").value("READY"));
        assertThat(available(pharmacy, scarce)).isZero();
        assertThat(available(pharmacy, rare)).isEqualTo(3);

        // The bon is handed over (already paid); the special order is collected through a sale
        call(post("/api/v1/customer-orders/" + voucher + "/delivery"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        call(post("/api/v1/customer-orders/" + voucher + "/cancellation"), pharmacy.ownerToken(), """
                {"reason":"trop tard"}""").andExpect(status().isUnprocessableContent());
        call(post("/api/v1/sales"), pharmacy.ownerToken(), """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-S1","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":2,"unitPriceCents":2000,"discountCents":0,"customerOrderId":"%s"}],
                 "payments":[{"method":"CASH","amountCents":4000}]}"""
                .formatted(UUID.randomUUID(), Instant.now(), rare, special))
                .andExpect(status().isCreated());
        call(get("/api/v1/customer-orders/" + special), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        assertThat(available(pharmacy, rare)).isEqualTo(3);
    }

    @Test
    void ordersAndInvoicesTravelAsExcelAndTheWalletBalanceIsRead() throws Exception {
        var pharmacy = onboardPharmacy("Excel Tahoua");
        var product = createProduct("IBUPROFENE 400MG CPR B/30", "NONE", 900);
        String supplier = read(call(post("/api/v1/procurement/suppliers"), pharmacy.ownerToken(), """
                {"name":"Copharni","leadTimeDays":1,"minOrderCents":0}""")
                .andExpect(status().isCreated()), "$.id");
        String order = read(call(post("/api/v1/procurement/orders"), pharmacy.ownerToken(), """
                {"supplierId":"%s","lines":[{"productId":"%s","quantity":12,"unitCostCents":450}]}"""
                .formatted(supplier, product))
                .andExpect(status().isCreated()), "$.id");

        byte[] orderBook = call(get("/api/v1/procurement/orders/" + order + "/xlsx"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(XlsxReader.isXlsx(orderBook)).isTrue();

        // The wholesaler's invoice, read back to pre-fill the receiving form
        String cip = JsonPath.read(body(call(get("/api/v1/procurement/orders/" + order), pharmacy.ownerToken(), null)),
                "$.lines[0].cip13");
        byte[] invoice = XlsxWriter.write(new XlsxWriter.Sheet("Facture", List.of("Code CIP", "Désignation", "Lot",
                "Péremption", "Qté", "Prix achat"), List.of(
                List.of(XlsxWriter.text(cip), XlsxWriter.text("IBUPROFENE"), XlsxWriter.text("L-77"),
                        XlsxWriter.date(LocalDate.now().plusYears(2)), XlsxWriter.number(12),
                        XlsxWriter.amount(450L, 0)),
                List.of(XlsxWriter.text("0000000000017"), XlsxWriter.text("INCONNU"), XlsxWriter.text("X"),
                        XlsxWriter.date(LocalDate.now().plusYears(1)), XlsxWriter.number(1),
                        XlsxWriter.amount(100L, 0)))));
        mvc.perform(multipart("/api/v1/procurement/orders/" + order + "/receiving/invoice")
                        .file(new MockMultipartFile("file", "facture.xlsx", "application/octet-stream", invoice))
                        .header("Authorization", "Bearer " + pharmacy.ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.unknown").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(product.toString()))
                .andExpect(jsonPath("$.lines[0].orderLineNo").value(1))
                .andExpect(jsonPath("$.lines[0].lotNumber").value("L-77"));

        // The wallet's external balance, read on the operator's app (SMS readings: PaymentSmsParserTest)
        call(post("/api/v1/payments/wallets/MOOV_MONEY/balance"), pharmacy.ownerToken(), """
                {"balanceCents":40000,"note":"lu sur l'application"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"));
        call(get("/api/v1/payments/wallets/balances"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.provider == 'MOOV_MONEY')].balanceCents").value(40_000));
    }

    // ------------------------------------------------------------------ helpers

    private String staff(Pharmacy pharmacy, String roleId, String name) throws Exception {
        String email = name + "-" + pharmacy.slug() + "@example.org";
        var registered = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"%s","roleIds":["%s"]}""".formatted(email, name, roleId))
                .andExpect(status().isCreated());
        return login(pharmacy.slug(), email, read(registered, "$.temporaryPassword"));
    }

    private String roleId(Pharmacy pharmacy, String code) throws Exception {
        var roles = call(get("/api/v1/roles"), pharmacy.ownerToken(), null).andExpect(status().isOk());
        return JsonPath.<List<String>>read(roles.andReturn().getResponse().getContentAsString(),
                "$[?(@.code == '" + code + "')].id").getFirst();
    }

    private static String sale(UUID product, int quantity, String customerId) {
        UUID id = UUID.randomUUID();
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",%s
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":500,"discountCents":0}],
                 "payments":[{"method":"CASH","amountCents":%d}]}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(),
                customerId == null ? "" : "\"customerId\":\"" + customerId + "\",", product, quantity, 500 * quantity);
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
