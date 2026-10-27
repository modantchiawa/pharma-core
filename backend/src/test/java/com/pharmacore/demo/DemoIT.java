package com.pharmacore.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Demonstration mode as QA will use it: the seeded pharmacies exist, their staff log in with the
 * documented accounts, the dashboard and the public directory have data, and the platform console
 * opens with the demo password.
 */
@TestPropertySource(properties = {
        "pharmacore.demo.enabled=true",
        "pharmacore.demo.platform-password=qa-secret",
        "pharmacore.demo.account-password=Demo2026!"})
class DemoIT extends IntegrationTest {

    @Test
    void demoDataIsSeededAndEveryEntryPointWorks() throws Exception {
        // platform console by password
        mvc.perform(post("/api/auth/platform-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnprocessableContent());
        String platform = JsonPath.read(mvc.perform(post("/api/auth/platform-login")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"qa-secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        String tenants = call(get("/api/platform/tenants"), platform, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> slugs = JsonPath.read(tenants, "$[*].slug");
        assertThat(slugs).contains("sahel", "lacouroussou");
        // registers in every synchronization state; DEMO-1 (seeded sales, open session) is synchronized
        String sahelId = JsonPath.<List<String>>read(tenants, "$[?(@.slug == 'sahel')].id").getFirst();
        String registers = call(get("/api/platform/tenants/" + sahelId + "/supervision"), platform, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminals.counts.total").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(registers, "$.terminals.terminals[?(@.terminalId == 'DEMO-1')].state"))
                .containsExactly("SYNCED");
        assertThat(JsonPath.<List<String>>read(registers, "$.terminals.terminals[*].state"))
                .containsExactlyInAnyOrder("SYNCED", "PENDING", "STALE", "NEVER");
        assertThat(JsonPath.<List<Integer>>read(registers, "$.terminals.terminals[?(@.state == 'PENDING')].pendingSales"))
                .containsExactly(3);
        assertThat(JsonPath.<List<Object>>read(registers, "$.accounts[*].lastLoginAt")).hasSize(2).doesNotContainNull();

        // staff accounts
        String owner = login("sahel", "titulaire@sahel.demo", "Demo2026!");
        String cashier = login("lacouroussou", "caisse@lacouroussou.demo", "Demo2026!");
        call(get("/api/v1/me"), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("CAISSIER"));

        // subscriptions: the Sahel paid well ahead, Lacouroussou is warned its paid period ends; nobody is blocked
        call(get("/api/v1/subscription"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.state.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.code").value("PRO"))
                .andExpect(jsonPath("$.payments.length()").value(3));
        call(get("/api/v1/subscription/state"), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("WARNING"))
                .andExpect(jsonPath("$.state.reason").value("PAYMENT_DUE"))
                .andExpect(jsonPath("$.plan").value("ESSENTIEL"));
        call(get("/api/v1/subscription"), cashier, null).andExpect(status().isForbidden());
        String subscriptions = call(get("/api/platform/subscriptions"), platform, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(subscriptions, "$[?(@.state.status == 'BLOCKED')]")).isEmpty();
        assertThat((List<?>) JsonPath.read(subscriptions, "$[?(@.slug == 'lacouroussou')].license.serial")).isNotEmpty();

        // two weeks of sales feed the dashboard; stock, customers and suppliers are there
        LocalDate today = LocalDate.now(DemoDataSeeder.ZONE);
        String dashboard = call(get("/api/v1/dashboard?from=" + today.minusDays(14) + "&to=" + today), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(dashboard, "$.sales.count")).intValue()).isGreaterThan(30);
        assertThat(((Number) JsonPath.read(dashboard, "$.stock.expiringLots")).intValue()).isGreaterThan(0);
        assertThat(((Number) JsonPath.read(dashboard, "$.receivables.debtors")).intValue()).isEqualTo(1);
        call(get("/api/v1/procurement/suppliers"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        // purchase orders at every stage of the follow-up, one of them late
        call(get("/api/v1/procurement/orders/summary"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts").value(1))
                .andExpect(jsonPath("$.open").value(3))
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.expectedToday").value(1));
        call(get("/api/v1/procurement/orders?scope=CLOSED"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("RECEIVED"));
        // every demo product is put away: order lines tell where to shelve the delivery
        String open = call(get("/api/v1/procurement/orders?scope=OPEN"), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(open, "$[*].lines[*]")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(open, "$[*].lines[?(@.locationCode == null)]")).isEmpty();
        assertThat((List<?>) JsonPath.read(open, "$[*].lines[*].locationName")).doesNotContainNull();
        String proposal = call(get("/api/v1/procurement/proposal"), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(proposal, "$.lines[?(@.supplierId != null)]")).isNotEmpty();
        assertThat((List<?>) JsonPath.read(proposal, "$.lines[?(@.supplierId == null)]")).hasSize(1);
        call(get("/api/v1/insurers"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].details.name").value("NIA Assurances"));
        call(get("/api/v1/cash/sessions/open?terminalId=DEMO-1"), owner, null)
                .andExpect(status().isOk());

        // every product is put away; the fridge and the narcotics safe hold the matching products
        call(get("/api/v1/inventory/stocks?unassigned=true"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        String locations = call(get("/api/v1/inventory/locations"), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(locations, "$[*]")).hasSizeGreaterThanOrEqualTo(8);
        String fridge = JsonPath.<List<String>>read(locations, "$[?(@.kind == 'FRIDGE')].id").getFirst();
        call(get("/api/v1/inventory/stocks?locationId=" + fridge), owner, null)
                .andExpect(jsonPath("$[0].designation").value("INSULATARD 100UI/ML FL 10ML"));
        String safe = JsonPath.<List<String>>read(locations, "$[?(@.kind == 'SAFE')].id").getFirst();
        call(get("/api/v1/inventory/stocks?locationId=" + safe), owner, null)
                .andExpect(jsonPath("$[0].designation").value("TRAMADOL 50MG GEL B/20"));

        // the counter's best sellers have a photo, served to the cashier as well
        String stockList = call(get("/api/v1/inventory/stocks"), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> photographed = JsonPath.read(stockList, "$[?(@.imageVersion != null)].productId");
        assertThat(photographed).hasSizeGreaterThanOrEqualTo(12);
        String cashierSahel = login("sahel", "caisse@sahel.demo", "Demo2026!");
        String version = JsonPath.<List<String>>read(stockList,
                "$[?(@.productId == '" + photographed.getFirst() + "')].imageVersion").getFirst();
        call(get("/api/v1/inventory/products/" + photographed.getFirst() + "/image?v=" + version), cashierSahel, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
        // internal control: the adjoint cancels paid sales far more than the team, the bell rings
        String control = call(get("/api/v1/control/overview?from=" + today.minusDays(14) + "&to=" + today), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Boolean>>read(control, "$.employees[?(@.displayName == 'Dr Ibrahim Souley')].flags"
                + "[?(@.metric == 'CANCELLATIONS_AFTER_PAYMENT')].flagged")).containsExactly(true);
        assertThat(JsonPath.<List<Integer>>read(control,
                "$.employees[?(@.displayName == 'Dr Ibrahim Souley')].outsideHours"))
                .hasSize(1).allMatch(n -> n >= 1);
        String feed = call(get("/api/v1/control/alerts"), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(feed, "$.alerts[*].kind")).contains("LARGE_ADJUSTMENT",
                "COUNT_DISCREPANCY", "OUTSIDE_HOURS", "CASH_VARIANCE");
        String register = call(get("/api/v1/control/narcotics?from=" + today.minusDays(14) + "&to=" + today), owner,
                null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(register,
                "$.products[?(@.designation == 'TRAMADOL 50MG GEL B/20')].lastCount.gap")).containsExactly(-2);
        // today's control is drawn at the first request and can be started from the counter
        call(post("/api/v1/control/daily-count"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.items.length()").value(6));

        // public directory: availability of paracetamol in Niamey and tonight's guard
        String availability = mvc.perform(get("/api/public/directory/availability?q=paracetamol&city=Niamey"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(availability, "$[*]")).isNotEmpty();
        String guards = mvc.perform(get("/api/public/directory/guards?city=Niamey&at="
                        + today.atTime(22, 0).atZone(DemoDataSeeder.ZONE).toInstant()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(guards, "$[*]")).hasSize(1);
    }
}
