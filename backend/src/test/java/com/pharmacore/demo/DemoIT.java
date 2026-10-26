package com.pharmacore.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

        // staff accounts
        String owner = login("sahel", "titulaire@sahel.demo", "Demo2026!");
        String cashier = login("lacouroussou", "caisse@lacouroussou.demo", "Demo2026!");
        call(get("/api/v1/me"), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("CAISSIER"));

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
        call(get("/api/v1/insurers"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].details.name").value("NIA Assurances"));
        call(get("/api/v1/cash/sessions/open?terminalId=DEMO-1"), owner, null)
                .andExpect(status().isOk());

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
