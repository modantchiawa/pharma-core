package com.pharmacore.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The vendor supervises each pharmacy's registers, hub link and accounts; the pharmacy sees its
 * own registers on its "Serveur local" page; off-line registers can be forgotten, never live ones,
 * and never from the cloud when they belong to a hub.
 */
class TerminalSupervisionIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void vendorSeesEveryRegisterWithItsStateAndAccountsWithoutSecrets() throws Exception {
        var zinder = onboardPharmacy("Supervision Zinder");
        var maradi = onboardPharmacy("Supervision Maradi");
        beat(zinder, "Z-SYNC", 0, Instant.now().minusSeconds(60), 0);
        beat(zinder, "Z-PEND", 3, Instant.now().minusSeconds(600), null);
        beat(zinder, "Z-NEVER", 0, null, null);
        beat(zinder, "Z-OFF", 1, Instant.now().minus(Duration.ofDays(2)), null);
        silence(zinder.tenantId(), "Z-OFF", Duration.ofDays(2));
        beat(maradi, "M-1", 0, Instant.now(), 0);
        staff(zinder, "CAISSIER", "caissier");

        String body = call(get("/api/platform/tenants/" + zinder.tenantId() + "/supervision"), platformToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pharmacy.slug").value(zinder.slug()))
                .andExpect(jsonPath("$.pharmacy.topology").value("CLOUD"))
                .andExpect(jsonPath("$.pharmacy.subscriptionPlan").value("PRO"))
                .andExpect(jsonPath("$.hub").doesNotExist())
                .andExpect(jsonPath("$.terminals.relayedByHub").value(false))
                .andExpect(jsonPath("$.terminals.staleAfterSeconds").value(300))
                .andExpect(jsonPath("$.terminals.counts.total").value(4))
                .andExpect(jsonPath("$.terminals.counts.synced").value(1))
                .andExpect(jsonPath("$.terminals.counts.pending").value(1))
                .andExpect(jsonPath("$.terminals.counts.stale").value(1))
                .andExpect(jsonPath("$.terminals.counts.never").value(1))
                .andExpect(jsonPath("$.terminals.counts.pendingSales").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat(state(body, "Z-SYNC")).isEqualTo("SYNCED");
        assertThat(state(body, "Z-PEND")).isEqualTo("PENDING");
        assertThat(state(body, "Z-NEVER")).isEqualTo("NEVER");
        assertThat(state(body, "Z-OFF")).isEqualTo("STALE");
        assertThat(JsonPath.<List<Object>>read(body, "$.terminals.terminals[?(@.terminalId == 'Z-SYNC')].rejectedSales"))
                .containsExactly(0);
        assertThat(JsonPath.<List<Object>>read(body, "$.terminals.terminals[?(@.terminalId == 'Z-PEND')].rejectedSales"))
                .as("a register that does not report rejections").containsExactly((Object) null);
        assertThat(JsonPath.<List<String>>read(body, "$.terminals.terminals[*].terminalId")).doesNotContain("M-1");

        // accounts: roles, state, last login from the audit trail, no secret of any kind
        assertThat(JsonPath.<List<String>>read(body, "$.accounts[*].email"))
                .containsExactlyInAnyOrder(zinder.ownerEmail(), "caissier-" + zinder.slug() + "@example.org");
        assertThat(JsonPath.<List<String>>read(body, "$.accounts[?(@.email == '" + zinder.ownerEmail()
                + "')].roles[*].code")).containsExactly("TITULAIRE");
        assertThat(JsonPath.<List<Object>>read(body, "$.accounts[*].lastLoginAt")).doesNotContainNull();
        assertThat(JsonPath.<List<Boolean>>read(body, "$.accounts[*].active")).containsOnly(true);
        assertThat(JsonPath.<List<Boolean>>read(body, "$.accounts[*].pinSet")).containsOnly(false);
        assertThat(body).doesNotContain("$2a$").doesNotContain("$2b$").doesNotContainIgnoringCase("hash")
                .doesNotContain(zinder.ownerPassword());

        // console list: counts per pharmacy, consistent with the detail
        String health = call(get("/api/platform/tenants/sync-health"), platformToken, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> zinderHealth = row(health, zinder.tenantId());
        assertThat(zinderHealth).containsEntry("terminals", 4).containsEntry("outOfSync", 2)
                .containsEntry("stale", 1).containsEntry("pendingSales", 4).containsEntry("topology", "CLOUD")
                .containsEntry("hubReachable", false);
        assertThat(zinderHealth.get("hubLastContactAt")).isNull();
        assertThat(row(health, maradi.tenantId())).containsEntry("terminals", 1).containsEntry("outOfSync", 0)
                .containsEntry("stale", 0);

        // platform only
        call(get("/api/platform/tenants/" + zinder.tenantId() + "/supervision"), zinder.ownerToken(), null)
                .andExpect(status().isForbidden());
        call(get("/api/platform/tenants/sync-health"), maradi.ownerToken(), null)
                .andExpect(status().isForbidden());
        call(delete("/api/platform/tenants/" + zinder.tenantId() + "/terminals/Z-OFF"), zinder.ownerToken(), null)
                .andExpect(status().isForbidden());
        call(get("/api/platform/tenants/" + UUID.randomUUID() + "/supervision"), platformToken, null)
                .andExpect(status().isNotFound());
    }

    @Test
    void pharmacySeesOnlyItsOwnRegistersOnItsServerPage() throws Exception {
        var tahoua = onboardPharmacy("Postes Tahoua");
        var dosso = onboardPharmacy("Postes Dosso");
        beat(tahoua, "TA-1", 0, Instant.now(), 2);
        beat(tahoua, "TA-2", 5, Instant.now().minusSeconds(120), 0);
        beat(dosso, "DO-1", 0, Instant.now(), 0);

        call(get("/api/v1/pos/terminals"), tahoua.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relayedByHub").value(false))
                .andExpect(jsonPath("$.counts.total").value(2))
                .andExpect(jsonPath("$.counts.pending").value(1))
                .andExpect(jsonPath("$.terminals[?(@.terminalId == 'TA-1')].state").value("SYNCED"))
                .andExpect(jsonPath("$.terminals[?(@.terminalId == 'TA-1')].rejectedSales").value(2))
                .andExpect(jsonPath("$.terminals[?(@.terminalId == 'TA-2')].state").value("PENDING"))
                .andExpect(jsonPath("$.terminals[?(@.terminalId == 'DO-1')]").isEmpty());
        call(get("/api/v1/pos/terminals"), dosso.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminals.length()").value(1))
                .andExpect(jsonPath("$.terminals[0].terminalId").value("DO-1"));

        // a cashier has neither the hub administration nor the audit right
        String cashier = staff(tahoua, "CAISSIER", "caissier");
        call(get("/api/v1/pos/terminals"), cashier, null).andExpect(status().isForbidden());
        call(delete("/api/v1/pos/terminals/TA-2"), cashier, null).andExpect(status().isForbidden());
        // another pharmacy's register is unknown here
        call(delete("/api/v1/pos/terminals/DO-1"), tahoua.ownerToken(), null).andExpect(status().isNotFound());
    }

    @Test
    void offlineRegistersAreForgottenButNeverLiveOnesNorThoseHoldingSales() throws Exception {
        var agadez = onboardPharmacy("Oubli Agadez");
        beat(agadez, "AG-LIVE", 0, Instant.now(), 0);
        beat(agadez, "AG-GONE", 0, null, null);
        beat(agadez, "AG-PHANTOM", 0, null, null);
        beat(agadez, "AG-LOST", 2, Instant.now().minus(Duration.ofDays(3)), 0);
        silence(agadez.tenantId(), "AG-GONE", Duration.ofDays(1));
        silence(agadez.tenantId(), "AG-PHANTOM", Duration.ofMinutes(6));
        silence(agadez.tenantId(), "AG-LOST", Duration.ofDays(3));

        call(delete("/api/v1/pos/terminals/AG-LIVE"), agadez.ownerToken(), null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("terminal-active"));
        call(delete("/api/v1/pos/terminals/AG-GONE"), agadez.ownerToken(), null)
                .andExpect(status().isNoContent());
        call(get("/api/v1/audit/events"), agadez.ownerToken(), null)
                .andExpect(jsonPath("$[?(@.action == 'TERMINAL_FORGOTTEN' && @.resourceId == 'AG-GONE')]").exists());

        // bulk: the phantom goes, the register still holding two sales stays
        call(post("/api/platform/tenants/" + agadez.tenantId() + "/terminals/forget-stale"), platformToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forgotten").value(1));
        String left = call(get("/api/v1/pos/terminals"), agadez.ownerToken(), null)
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(left, "$.terminals[*].terminalId"))
                .containsExactlyInAnyOrder("AG-LIVE", "AG-LOST");

        // the vendor may still forget it explicitly, once it is off-line
        call(delete("/api/platform/tenants/" + agadez.tenantId() + "/terminals/AG-LOST"), platformToken, null)
                .andExpect(status().isNoContent());
        call(delete("/api/platform/tenants/" + agadez.tenantId() + "/terminals/AG-LOST"), platformToken, null)
                .andExpect(status().isNotFound());
        call(delete("/api/platform/tenants/" + agadez.tenantId() + "/terminals/AG-LIVE"), platformToken, null)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void cloudViewOfAHubPharmacyIsDatedByTheHubsLastContact() throws Exception {
        var niamey = onboardPharmacy("Hub Niamey");
        Instant hubContact = Instant.now().minus(Duration.ofDays(2));
        sql("UPDATE tenant SET topology = 'HUB' WHERE id = :tenant", niamey.tenantId(), Map.of());
        sql("""
                INSERT INTO hub_node (tenant_id, node_id, app_version, paired_at, last_push_at, last_pull_at, last_seen_at)
                VALUES (:tenant, 'hub-niamey', '1.4.0', :paired, :contact, :contact, :contact)""",
                niamey.tenantId(), Map.of("paired", Timestamp.from(hubContact.minus(Duration.ofDays(30))),
                        "contact", Timestamp.from(hubContact)));
        // replicated by the hub just before it lost the cloud: in contact with the hub at that time
        sql("""
                INSERT INTO terminal_heartbeat (tenant_id, terminal_id, app_version, pending_sales, last_sync_at, last_seen_at)
                VALUES (:tenant, 'NY-1', '1.4.0', 0, :sync, :seen),
                       (:tenant, 'NY-2', '1.4.0', 2, :sync, :seen)""",
                niamey.tenantId(), Map.of("sync", Timestamp.from(hubContact.minusSeconds(120)),
                        "seen", Timestamp.from(hubContact.minusSeconds(30))));

        String body = call(get("/api/platform/tenants/" + niamey.tenantId() + "/supervision"), platformToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pharmacy.topology").value("HUB"))
                .andExpect(jsonPath("$.hub.nodeId").value("hub-niamey"))
                .andExpect(jsonPath("$.hub.appVersion").value("1.4.0"))
                .andExpect(jsonPath("$.hub.reachable").value(false))
                .andExpect(jsonPath("$.terminals.relayedByHub").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$.terminals.observedAt")))
                .isEqualTo(Instant.parse(JsonPath.read(body, "$.hub.lastContactAt")));
        assertThat(state(body, "NY-1")).isEqualTo("SYNCED");
        assertThat(state(body, "NY-2")).isEqualTo("PENDING");

        Map<String, Object> health = row(call(get("/api/platform/tenants/sync-health"), platformToken, null)
                .andReturn().getResponse().getContentAsString(), niamey.tenantId());
        assertThat(health).containsEntry("topology", "HUB").containsEntry("hubReachable", false)
                .containsEntry("stale", 0).containsEntry("outOfSync", 1);
        assertThat(health.get("hubLastContactAt")).isNotNull().isEqualTo(health.get("observedAt"));

        // the cloud only holds a replica: forgetting belongs to the hub
        call(delete("/api/platform/tenants/" + niamey.tenantId() + "/terminals/NY-1"), platformToken, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("hub-managed"));
        call(post("/api/platform/tenants/" + niamey.tenantId() + "/terminals/forget-stale"), platformToken, null)
                .andExpect(status().isUnprocessableContent());
        call(get("/api/v1/pos/terminals"), niamey.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relayedByHub").value(true))
                .andExpect(jsonPath("$.terminals.length()").value(2));
    }

    // ------------------------------------------------------------------ fixtures

    @Test
    void registersGetANameOnTopOfTheirUniqueId() throws Exception {
        var niamey = onboardPharmacy("Postes Niamey");
        var dosso = onboardPharmacy("Postes Dosso");
        String owner = niamey.ownerToken();
        beat(niamey, "T7K2MX9", 0, Instant.now(), 0);
        beat(niamey, "TQ4HB2C", 0, Instant.now(), 0);

        call(put("/api/v1/pos/terminals/T7K2MX9/name"), owner, """
                {"name":"  Caisse 1 ","location":"Comptoir d'entrée"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalId").value("T7K2MX9"))
                .andExpect(jsonPath("$.name").value("Caisse 1"))
                .andExpect(jsonPath("$.location").value("Comptoir d'entrée"));
        // a register can be named before it ever reported
        call(put("/api/v1/pos/terminals/TNEWPC1/name"), owner, """
                {"name":"Bureau du titulaire"}""")
                .andExpect(status().isOk());
        // two registers never share a name, whatever the case
        call(put("/api/v1/pos/terminals/TQ4HB2C/name"), owner, """
                {"name":"caisse 1"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("terminal-name-taken"));
        // renaming a register to its own name is fine
        call(put("/api/v1/pos/terminals/T7K2MX9/name"), owner, """
                {"name":"CAISSE 1","location":"Comptoir d'entrée"}""")
                .andExpect(status().isOk());

        call(get("/api/v1/pos/terminals/names"), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Bureau du titulaire"))
                .andExpect(jsonPath("$[1].name").value("CAISSE 1"));
        String board = call(get("/api/v1/pos/terminals"), owner, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(board, "$.terminals[?(@.terminalId == 'T7K2MX9')].name"))
                .containsExactly("CAISSE 1");
        assertThat(JsonPath.<List<String>>read(board, "$.terminals[?(@.terminalId == 'TQ4HB2C')].name"))
                .containsExactly((String) null);

        // a cashier reads the names (the counter shows its own) but cannot rename
        String cashier = staff(niamey, "CAISSIER", "caissier-postes");
        call(get("/api/v1/pos/terminals/names"), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        call(put("/api/v1/pos/terminals/TQ4HB2C/name"), cashier, """
                {"name":"Caisse 2"}""")
                .andExpect(status().isForbidden());

        // blank name: back to the id alone
        call(put("/api/v1/pos/terminals/TNEWPC1/name"), owner, """
                {"name":" "}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").doesNotExist());
        call(get("/api/v1/pos/terminals/names"), owner, null)
                .andExpect(jsonPath("$.length()").value(1));

        // forgetting a register drops its name too
        silence(niamey.tenantId(), "T7K2MX9", Duration.ofDays(1));
        call(delete("/api/v1/pos/terminals/T7K2MX9"), owner, null).andExpect(status().isNoContent());
        call(get("/api/v1/pos/terminals/names"), owner, null)
                .andExpect(jsonPath("$.length()").value(0));

        // another pharmacy sees none of these names
        call(put("/api/v1/pos/terminals/TQ4HB2C/name"), owner, """
                {"name":"Caisse 2"}""")
                .andExpect(status().isOk());
        call(get("/api/v1/pos/terminals/names"), dosso.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
        call(put("/api/v1/pos/terminals/TQ4HB2C/name"), dosso.ownerToken(), """
                {"name":"Caisse 2"}""")
                .andExpect(status().isOk());
    }

    private void beat(Pharmacy pharmacy, String terminalId, int pending, Instant lastSync, Integer rejected)
            throws Exception {
        call(post("/api/v1/pos/heartbeat"), pharmacy.ownerToken(), """
                {"terminalId":"%s","appVersion":"0.1.0","pendingSales":%d,"lastSyncAt":%s,"rejectedSales":%s}"""
                .formatted(terminalId, pending, lastSync == null ? "null" : "\"" + lastSync + "\"", rejected))
                .andExpect(status().isNoContent());
    }

    private void silence(UUID tenantId, String terminalId, Duration silence) {
        sql("UPDATE terminal_heartbeat SET last_seen_at = :seen WHERE tenant_id = :tenant AND terminal_id = :terminal",
                tenantId, Map.of("seen", Timestamp.from(Instant.now().minus(silence)), "terminal", terminalId));
    }

    private void sql(String statement, UUID tenantId, Map<String, Object> params) {
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("test-fixture"));
        var tx = new TransactionTemplate(transactionManager);
        TenantContext.run(context, () -> tx.executeWithoutResult(status -> {
            var spec = jdbc.sql(statement).param("tenant", tenantId);
            for (var entry : params.entrySet()) {
                spec = spec.param(entry.getKey(), entry.getValue());
            }
            spec.update();
        }));
    }

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

    private static String state(String body, String terminalId) {
        return JsonPath.<List<String>>read(body, "$.terminals.terminals[?(@.terminalId == '" + terminalId + "')].state")
                .getFirst();
    }

    private static Map<String, Object> row(String health, UUID tenantId) {
        return JsonPath.<List<Map<String, Object>>>read(health, "$[?(@.tenantId == '" + tenantId + "')]").getFirst();
    }
}
