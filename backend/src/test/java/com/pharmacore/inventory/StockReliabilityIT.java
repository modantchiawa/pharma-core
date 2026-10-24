package com.pharmacore.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Blind physical count by a technician, validation by the pharmacist, ledger integrity check
 * catching a quantity altered behind the application's back, expired lots written off.
 */
class StockReliabilityIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void blindCountIntegrityCheckAndExpiryWriteOff() throws Exception {
        var pharmacy = onboardPharmacy("Inventaire Zinder");
        var paracetamol = createProduct("PARACETAMOL 1G CPR B/8", "NONE", 700);
        var ibuprofen = createProduct("IBUPROFENE 400MG CPR B/20", "NONE", 1_200);
        UUID lotP = receiveLot(pharmacy, paracetamol, "P-1", "2028-03-31", 30);
        UUID lotI = receiveLot(pharmacy, ibuprofen, "I-1", "2027-11-30", 10);

        // A technician (PREPARATEUR) may count but not validate
        String counter = staff(pharmacy, "PREPARATEUR", "compteur");

        // --- blind count
        var started = call(post("/api/v1/inventory/counts"), counter, """
                {"label":"Inventaire rayon A"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.lineCount").value(2))
                .andExpect(jsonPath("$.lines[0].expectedQuantity").doesNotExist())
                .andExpect(jsonPath("$.varianceUnits").doesNotExist());
        String countId = read(started, "$.id");
        String body = started.andReturn().getResponse().getContentAsString();
        List<String> lotNumbers = JsonPath.read(body, "$.lines[*].lotNumber");
        assertThat(lotNumbers).containsExactlyInAnyOrder("P-1", "I-1");
        String lineP = JsonPath.<List<String>>read(body, "$.lines[?(@.lotNumber == 'P-1')].id").getFirst();

        // The pharmacist sees the frozen expectation
        call(get("/api/v1/inventory/counts/" + countId), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.lotNumber == 'P-1')].expectedQuantity").value(30));

        // Counting: 28 paracetamol by line, 10 ibuprofen by scan
        call(post("/api/v1/inventory/counts/" + countId + "/lines/" + lineP), counter, """
                {"quantity":28}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countedLines").value(1));
        call(post("/api/v1/inventory/counts/" + countId + "/scans"), counter, """
                {"productId":"%s","lotNumber":"i-1","quantity":10}""".formatted(ibuprofen))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countedLines").value(2));
        call(post("/api/v1/inventory/counts/" + countId + "/scans"), counter, """
                {"productId":"%s","lotNumber":"ZZZ","quantity":1}""".formatted(ibuprofen))
                .andExpect(status().isUnprocessableContent());

        // Only a validator closes the count
        call(post("/api/v1/inventory/counts/" + countId + "/validation"), counter, "{}")
                .andExpect(status().isForbidden());
        // Meanwhile a sale-like withdrawal would shift the live quantity; the variance is applied on top of it
        call(post("/api/v1/inventory/counts/" + countId + "/validation"), pharmacy.ownerToken(), """
                {"notes":"Deux boîtes cassées"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.varianceUnits").value(-2))
                .andExpect(jsonPath("$.varianceValueCents").value(-240))
                .andExpect(jsonPath("$.lines[?(@.lotNumber == 'P-1')].variance").value(-2));
        assertThat(available(pharmacy, paracetamol)).isEqualTo(28);
        assertThat(available(pharmacy, ibuprofen)).isEqualTo(10);
        call(post("/api/v1/inventory/counts/" + countId + "/lines/" + lineP), counter, """
                {"quantity":27}""")
                .andExpect(status().isUnprocessableContent());
        call(get("/api/v1/inventory/counts"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(countId));

        // --- integrity: the ledger explains every unit on the shelf
        call(post("/api/v1/inventory/integrity-checks"), pharmacy.ownerToken(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lotsChecked").value(2))
                .andExpect(jsonPath("$.mismatches").value(0));
        // A quantity altered behind the application's back (no movement) is caught
        sql(pharmacy.tenantId(), "UPDATE stock_lot SET quantity = quantity + 5 WHERE id = '" + lotP + "'");
        call(post("/api/v1/inventory/integrity-checks"), pharmacy.ownerToken(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mismatches").value(1))
                .andExpect(jsonPath("$.details[0].kind").value("LOT_QUANTITY"))
                .andExpect(jsonPath("$.details[0].lotNumber").value("P-1"))
                .andExpect(jsonPath("$.details[0].stored").value(33))
                .andExpect(jsonPath("$.details[0].computed").value(28));
        call(get("/api/v1/inventory/integrity-checks"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mismatches").value(1));
        sql(pharmacy.tenantId(), "UPDATE stock_lot SET quantity = quantity - 5 WHERE id = '" + lotP + "'");

        // --- expiry: a lot that expired on the shelf (inserted with its receipt movement, the API refuses expired lots)
        String stockId = sql(pharmacy.tenantId(),
                "SELECT id FROM stock WHERE product_id = '" + ibuprofen + "'", String.class);
        UUID expiredLot = UUID.randomUUID();
        sql(pharmacy.tenantId(), """
                INSERT INTO stock_lot (id, tenant_id, stock_id, lot_number, expiry_date, quantity, unit_cost_cents, received_at)
                VALUES ('%s', '%s', '%s', 'OLD-1', '2024-01-31', 7, 300, now() - interval '400 days')"""
                .formatted(expiredLot, pharmacy.tenantId(), stockId));
        sql(pharmacy.tenantId(), """
                INSERT INTO stock_movement (id, tenant_id, stock_id, lot_id, movement_type, quantity, reference_type,
                                            reference_id, occurred_at)
                VALUES ('%s', '%s', '%s', '%s', 'RECEIPT', 7, 'RECEIPT', '%s', now() - interval '400 days')"""
                .formatted(UUID.randomUUID(), pharmacy.tenantId(), stockId, expiredLot, expiredLot));
        call(get("/api/v1/inventory/expiry-alerts?withinDays=0"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[?(@.lotNumber == 'OLD-1')].expired").value(true));
        call(post("/api/v1/inventory/write-offs/run"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lotNumber").value("OLD-1"))
                .andExpect(jsonPath("$[0].quantity").value(7))
                .andExpect(jsonPath("$[0].valueCents").value(2_100));
        call(post("/api/v1/inventory/write-offs/run"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/inventory/write-offs"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lotNumber").value("OLD-1"));
        call(get("/api/v1/inventory/expiry-alerts?withinDays=0"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$[?(@.lotNumber == 'OLD-1')]").isEmpty());
        // Ledger still consistent after the write-off
        call(post("/api/v1/inventory/integrity-checks"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.lotsChecked").value(3))
                .andExpect(jsonPath("$.mismatches").value(0));
        assertThat(lotI).isNotNull();
    }

    /** Registers a staff member with one predefined role and returns their access token. */
    private String staff(Pharmacy pharmacy, String roleCode, String name) throws Exception {
        var roles = call(get("/api/v1/roles"), pharmacy.ownerToken(), null).andExpect(status().isOk());
        String roleId = JsonPath.<List<String>>read(roles.andReturn().getResponse().getContentAsString(),
                "$[?(@.code == '" + roleCode + "')].id").getFirst();
        String email = name + "-" + pharmacy.slug() + "@example.org";
        var registered = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"%s","roleIds":["%s"]}""".formatted(email, name, roleId))
                .andExpect(status().isCreated());
        return login(pharmacy.slug(), email, read(registered, "$.temporaryPassword"));
    }

    private void sql(UUID tenantId, String statement) {
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("test-fixture"));
        var tx = new TransactionTemplate(transactionManager);
        TenantContext.run(context, () -> tx.executeWithoutResult(status -> jdbc.sql(statement).update()));
        assertThat(tenantId).isNotNull();
    }

    private <T> T sql(UUID tenantId, String query, Class<T> type) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test-fixture", new com.pharmacore.shared.context.TenantId(tenantId)));
        var tx = new TransactionTemplate(transactionManager);
        return TenantContext.call(context, () -> tx.execute(status -> jdbc.sql(query).query(type).single()));
    }
}
