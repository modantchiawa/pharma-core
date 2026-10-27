package com.pharmacore.identity;

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
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The owner sets how long an unattended terminal stays open; every token carries the delays so
 * that terminals apply them offline, the bounds are enforced by the server, each pharmacy keeps
 * its own delays, and the locks and logouts decided by a terminal are journaled.
 */
class SessionPolicyIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void delaysAreSetBoundedSentWithTokensAndIsolated() throws Exception {
        var pharmacy = onboardPharmacy("Veille Dosso");
        var other = onboardPharmacy("Veille Tillaberi");
        String owner = pharmacy.ownerToken();

        // --- defaults: lock after 5 minutes, close after 30, sent with the login
        call(get("/api/v1/pharmacy/session-policy"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockAfterMinutes").value(5))
                .andExpect(jsonPath("$.logoutAfterMinutes").value(30));
        loginResponse(pharmacy)
                .andExpect(jsonPath("$.sessionPolicy.lockAfterMinutes").value(5))
                .andExpect(jsonPath("$.sessionPolicy.logoutAfterMinutes").value(30));

        // --- the owner changes them; out of bounds or inconsistent delays are refused
        call(put("/api/v1/pharmacy/session-policy"), owner, """
                {"lockAfterMinutes":10,"logoutAfterMinutes":60}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockAfterMinutes").value(10));
        call(put("/api/v1/pharmacy/session-policy"), owner, """
                {"lockAfterMinutes":200,"logoutAfterMinutes":720}""")
                .andExpect(status().isBadRequest());
        call(put("/api/v1/pharmacy/session-policy"), owner, """
                {"lockAfterMinutes":0,"logoutAfterMinutes":2}""")
                .andExpect(status().isBadRequest());
        call(put("/api/v1/pharmacy/session-policy"), owner, """
                {"lockAfterMinutes":45,"logoutAfterMinutes":30}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("session-policy"))
                .andExpect(jsonPath("$.detail").value("La déconnexion doit intervenir après le verrouillage"));
        loginResponse(pharmacy)
                .andExpect(jsonPath("$.sessionPolicy.lockAfterMinutes").value(10))
                .andExpect(jsonPath("$.sessionPolicy.logoutAfterMinutes").value(60));
        assertThat(auditDetails(pharmacy.tenantId(), "SESSION_POLICY_UPDATED"))
                .singleElement().satisfies(details -> assertThat(details)
                        .contains("\"lockAfterMinutes\":\"10\"", "\"previousLockAfterMinutes\":\"5\""));

        // --- "never lock" is allowed, the logout still applies
        call(put("/api/v1/pharmacy/session-policy"), owner, """
                {"lockAfterMinutes":0,"logoutAfterMinutes":45}""")
                .andExpect(status().isOk());

        // --- a cashier reads the delays but cannot change them
        String cashier = cashier(pharmacy);
        call(get("/api/v1/pharmacy/session-policy"), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockAfterMinutes").value(0))
                .andExpect(jsonPath("$.logoutAfterMinutes").value(45));
        call(put("/api/v1/pharmacy/session-policy"), cashier, """
                {"lockAfterMinutes":1,"logoutAfterMinutes":5}""")
                .andExpect(status().isForbidden());

        // --- the other pharmacy keeps its own defaults
        call(get("/api/v1/pharmacy/session-policy"), other.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockAfterMinutes").value(5))
                .andExpect(jsonPath("$.logoutAfterMinutes").value(30));
        assertThat(auditDetails(other.tenantId(), "SESSION_POLICY_UPDATED")).isEmpty();

        // --- locks and logouts decided by a terminal are journaled under the user
        call(post("/api/v1/me/session-events"), cashier, """
                {"event":"IDLE_LOCK","terminalId":"T-TEST","idleMinutes":10}""")
                .andExpect(status().isNoContent());
        call(post("/api/v1/me/session-events"), cashier, """
                {"event":"PIN_FAILURES_LOGOUT","terminalId":"T-TEST","idleMinutes":11}""")
                .andExpect(status().isNoContent());
        call(post("/api/v1/me/session-events"), cashier, """
                {"event":"SOMETHING_ELSE","terminalId":"T-TEST","idleMinutes":1}""")
                .andExpect(status().isBadRequest());
        assertThat(auditDetails(pharmacy.tenantId(), "SESSION_LOCKED"))
                .singleElement().satisfies(details -> assertThat(details).contains("IDLE_LOCK", "T-TEST"));
        assertThat(auditDetails(pharmacy.tenantId(), "SESSION_PIN_LOGOUT")).hasSize(1);
        assertThat(auditDetails(other.tenantId(), "SESSION_LOCKED")).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions loginResponse(Pharmacy pharmacy) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"pharmacy":"%s","email":"%s","password":"%s","terminalId":"T-TEST"}
                        """.formatted(pharmacy.slug(), pharmacy.ownerEmail(), pharmacy.ownerPassword())))
                .andExpect(status().isOk());
    }

    private String cashier(Pharmacy pharmacy) throws Exception {
        String role = JsonPath.<List<String>>read(call(get("/api/v1/roles"), pharmacy.ownerToken(), null)
                .andReturn().getResponse().getContentAsString(), "$[?(@.code == 'CAISSIER')].id").getFirst();
        String email = "cashier-" + pharmacy.slug() + "@example.org";
        var registration = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"Caissier","roleIds":["%s"]}""".formatted(email, role))
                .andExpect(status().isCreated());
        return login(pharmacy.slug(), email, read(registration, "$.temporaryPassword"));
    }

    private List<String> auditDetails(UUID tenant, String action) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test", new TenantId(tenant)));
        return TenantContext.call(context, () -> new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT details::text FROM audit_event WHERE action = :action ORDER BY sequence_no")
                        .param("action", action)
                        .query(String.class)
                        .list()));
    }
}
