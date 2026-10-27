package com.pharmacore.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.support.IntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purchase orders from the proposal to the shelf: drafts generated per supplier (idempotent),
 * sending against the supplier minimum, partial delivery, short closure, delivery date changes,
 * late orders and the tracking figures, each pharmacy seeing only its own orders.
 */
class ProcurementIT extends IntegrationTest {

    private static final String API = "/api/v1/procurement";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void ordersAreGeneratedSentReceivedClosedAndTracked() throws Exception {
        var pharmacy = onboardPharmacy("Commandes Maradi");
        String owner = pharmacy.ownerToken();
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Niamey"));

        UUID doliprane = productBelowThreshold(pharmacy, "DOLIPRANE 1000MG CPR B/8");
        UUID amoxicillin = productBelowThreshold(pharmacy, "AMOXICILLINE 500MG GEL B/12");
        UUID coartem = productBelowThreshold(pharmacy, "COARTEM 80/480MG CPR B/6");
        UUID thermometer = productBelowThreshold(pharmacy, "THERMOMETRE DIGITAL");

        String laborex = supplier(owner, "Laborex Niger", 2, 0);
        String copharni = supplier(owner, "Copharni", 2, 100_000);
        link(owner, laborex, doliprane, 900, 1, true);
        link(owner, laborex, amoxicillin, 1_260, 10, true);
        link(owner, copharni, coartem, 1_870, 1, true);
        // also sold by Copharni, but Laborex is the preferred supplier: the line goes to Laborex only
        link(owner, copharni, doliprane, 950, 1, false);

        // --- generation from "all suppliers": one draft per supplier, unassigned lines reported
        var generated = call(post(API + "/orders/generate"), owner, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.unassigned.length()").value(1))
                .andExpect(jsonPath("$.unassigned[0].productId").value(thermometer.toString()));
        String laborexOrder = orderOf(generated, laborex);
        String copharniOrder = orderOf(generated, copharni);
        call(get(API + "/orders/" + laborexOrder), owner, null)
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[?(@.productId == '" + amoxicillin + "')].quantityOrdered").value(30))
                .andExpect(jsonPath("$.lines[?(@.productId == '" + doliprane + "')].reason").value("sous le seuil"))
                .andExpect(jsonPath("$.supplier.minOrderCents").value(0))
                .andExpect(jsonPath("$.supplier.whatsapp").value("+22790740000"))
                .andExpect(jsonPath("$.totalCents").value(30 * 900 + 30 * 1_260));
        call(get(API + "/orders/" + copharniOrder), owner, null)
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value(coartem.toString()));

        // --- generating again replaces the drafts' lines instead of adding drafts
        var regenerated = call(post(API + "/orders/generate"), owner, """
                {"overrides":[{"productId":"%s","quantity":0},{"productId":"%s","quantity":45}]}
                """.formatted(amoxicillin, doliprane))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(2));
        assertThat(orderOf(regenerated, laborex)).isEqualTo(laborexOrder);
        assertThat(orderOf(regenerated, copharni)).isEqualTo(copharniOrder);
        call(get(API + "/orders?scope=DRAFTS"), owner, null)
                .andExpect(jsonPath("$.length()").value(2));
        call(get(API + "/orders/" + laborexOrder), owner, null)
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].quantityOrdered").value(45))
                .andExpect(jsonPath("$.lines[0].suggestedQuantity").value(30))
                .andExpect(jsonPath("$.totalCents").value(45 * 900));
        // one supplier only
        call(post(API + "/orders/generate"), owner, """
                {"supplierId":"%s"}""".formatted(copharni))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].id").value(copharniOrder))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unassigned.length()").value(0));

        // --- sending: the supplier minimum is enforced at that point
        call(post(API + "/orders/" + copharniOrder + "/send"), owner, null)
                .andExpect(status().isUnprocessableContent());
        call(post(API + "/orders/" + laborexOrder + "/send"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.expectedAt").value(today.plusDays(2).toString()))
                .andExpect(jsonPath("$.overdue").value(false));
        // quantities are frozen once the order has left
        call(put(API + "/orders/" + laborexOrder), owner, """
                {"supplierId":"%s","lines":[{"productId":"%s","quantity":10}]}""".formatted(laborex, doliprane))
                .andExpect(status().isUnprocessableContent());

        // --- new delivery date: open orders only, never in the past
        call(put(API + "/orders/" + copharniOrder + "/expected-date"), owner, """
                {"expectedAt":"%s"}""".formatted(today.plusDays(1)))
                .andExpect(status().isUnprocessableContent());
        call(put(API + "/orders/" + laborexOrder + "/expected-date"), owner, """
                {"expectedAt":"%s"}""".formatted(today.minusDays(1)))
                .andExpect(status().isUnprocessableContent());
        call(put(API + "/orders/" + laborexOrder + "/expected-date"), owner, """
                {"expectedAt":"%s"}""".formatted(today.plusDays(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAt").value(today.plusDays(4).toString()));

        // --- partial delivery
        call(post(API + "/receipts"), owner, """
                {"supplierId":"%s","purchaseOrderId":"%s","supplierInvoiceNumber":"LBX-F-881","invoiceDate":"%s",
                 "lines":[{"productId":"%s","lotNumber":"D-1","expiryDate":"%s","quantity":20,"unitCostCents":900,
                           "vatRate":0}]}""".formatted(laborex, laborexOrder, today, doliprane, today.plusYears(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_RECEIVED"));
        assertThat(available(pharmacy, doliprane)).isEqualTo(10 + 20);
        call(get(API + "/orders/" + laborexOrder), owner, null)
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.orderedUnits").value(45))
                .andExpect(jsonPath("$.receivedUnits").value(20))
                .andExpect(jsonPath("$.receivedCents").value(20 * 900))
                .andExpect(jsonPath("$.remainingCents").value(25 * 900))
                .andExpect(jsonPath("$.shortClosed").value(false));
        call(get(API + "/orders/" + laborexOrder + "/receipts"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].supplierInvoiceNumber").value("LBX-F-881"))
                .andExpect(jsonPath("$[0].invoiceDate").value(today.toString()))
                .andExpect(jsonPath("$[0].totalExclVatCents").value(18_000))
                .andExpect(jsonPath("$[0].vatCents").value(0))
                .andExpect(jsonPath("$[0].lines").value(1))
                .andExpect(jsonPath("$[0].units").value(20))
                .andExpect(jsonPath("$[0].receivedBy").value("Dr Commandes Maradi"));

        // --- late: the announced day has passed in the pharmacy's calendar
        sql("UPDATE purchase_order SET expected_at = '" + today.minusDays(3) + "' WHERE id = '" + laborexOrder + "'");
        call(get(API + "/orders/" + laborexOrder), owner, null)
                .andExpect(jsonPath("$.overdue").value(true));
        call(get(API + "/orders/summary"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.drafts").value(1))
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.expectedToday").value(0))
                .andExpect(jsonPath("$.openAmountCents").value(25 * 900));
        call(get(API + "/orders?scope=OVERDUE"), owner, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(laborexOrder));
        call(get(API + "/orders?scope=OPEN&supplierId=" + copharni), owner, null)
                .andExpect(jsonPath("$.length()").value(0));
        call(get(API + "/orders?scope=ALL&supplierId=" + copharni), owner, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(copharniOrder));
        // the supplier announces delivery for today: no longer late, expected today
        call(put(API + "/orders/" + laborexOrder + "/expected-date"), owner, """
                {"expectedAt":"%s"}""".formatted(today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(false));
        call(get(API + "/orders/summary"), owner, null)
                .andExpect(jsonPath("$.overdue").value(0))
                .andExpect(jsonPath("$.expectedToday").value(1));

        // --- the rest will never come: short closure
        call(post(API + "/orders/" + laborexOrder + "/closure"), owner, """
                {"reason":"Rupture chez le grossiste"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.shortClosed").value(true))
                .andExpect(jsonPath("$.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.remainingCents").value(0));
        call(post(API + "/orders/" + laborexOrder + "/closure"), owner, null)
                .andExpect(status().isUnprocessableContent());
        call(put(API + "/orders/" + laborexOrder + "/expected-date"), owner, """
                {"expectedAt":"%s"}""".formatted(today.plusDays(1)))
                .andExpect(status().isUnprocessableContent());
        call(get(API + "/orders?scope=CLOSED"), owner, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].shortClosed").value(true));
        call(get(API + "/orders/summary"), owner, null)
                .andExpect(jsonPath("$.open").value(0))
                .andExpect(jsonPath("$.openAmountCents").value(0));

        // a sent order with nothing delivered is cancelled, not closed
        var next = call(post(API + "/orders/generate"), owner, """
                {"supplierId":"%s"}""".formatted(laborex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        String nextOrder = orderOf(next, laborex);
        call(post(API + "/orders/" + nextOrder + "/send"), owner, null).andExpect(status().isOk());
        call(post(API + "/orders/" + nextOrder + "/closure"), owner, null)
                .andExpect(status().isUnprocessableContent());
        call(post(API + "/orders/" + nextOrder + "/cancellation"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.shortClosed").value(false));
        call(post(API + "/orders/" + nextOrder + "/closure"), owner, null)
                .andExpect(status().isUnprocessableContent());

        // --- another pharmacy sees none of it
        var other = onboardPharmacy("Commandes Tahoua");
        call(get(API + "/orders/" + laborexOrder), other.ownerToken(), null).andExpect(status().isNotFound());
        call(get(API + "/orders/" + laborexOrder + "/receipts"), other.ownerToken(), null)
                .andExpect(status().isNotFound());
        call(post(API + "/orders/" + copharniOrder + "/closure"), other.ownerToken(), null)
                .andExpect(status().isNotFound());
        call(put(API + "/orders/" + copharniOrder + "/expected-date"), other.ownerToken(), """
                {"expectedAt":"%s"}""".formatted(today.plusDays(1)))
                .andExpect(status().isNotFound());
        call(get(API + "/orders"), other.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
        call(get(API + "/orders/summary"), other.ownerToken(), null)
                .andExpect(jsonPath("$.drafts").value(0))
                .andExpect(jsonPath("$.open").value(0));
        call(post(API + "/orders/generate"), other.ownerToken(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders.length()").value(0))
                .andExpect(jsonPath("$.unassigned.length()").value(0));
        call(get(API + "/orders?scope=DRAFTS"), owner, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(copharniOrder));
    }

    /** A product with 10 units on the shelf under a minimum of 50: the proposal orders 30. */
    private UUID productBelowThreshold(Pharmacy pharmacy, String designation) throws Exception {
        UUID product = createProduct(designation, "NONE", 1_500);
        receiveLot(pharmacy, product, "L-" + shortId(), LocalDate.now().plusYears(2).toString(), 10);
        sql("UPDATE stock SET reorder_threshold = 50, reorder_quantity = 30 WHERE tenant_id = '"
                + pharmacy.tenantId() + "' AND product_id = '" + product + "'");
        return product;
    }

    private String supplier(String token, String name, int leadTimeDays, long minOrderCents) throws Exception {
        return read(call(post(API + "/suppliers"), token, """
                {"name":"%s","whatsapp":"+22790740000","email":"commandes@example.ne","leadTimeDays":%d,
                 "minOrderCents":%d}""".formatted(name, leadTimeDays, minOrderCents))
                .andExpect(status().isCreated()), "$.id");
    }

    private void link(String token, String supplierId, UUID productId, long priceCents, int packSize,
                      boolean preferred) throws Exception {
        call(put(API + "/suppliers/" + supplierId + "/products/" + productId), token, """
                {"purchasePriceCents":%d,"packSize":%d,"preferred":%s}""".formatted(priceCents, packSize, preferred))
                .andExpect(status().isNoContent());
    }

    private static String orderOf(ResultActions generation, String supplierId) throws Exception {
        List<String> ids = JsonPath.read(generation.andReturn().getResponse().getContentAsString(),
                "$.orders[?(@.supplierId == '" + supplierId + "')].id");
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private void sql(String statement) {
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("test-fixture"));
        var tx = new TransactionTemplate(transactionManager);
        TenantContext.run(context, () -> tx.executeWithoutResult(status -> jdbc.sql(statement).update()));
    }
}
