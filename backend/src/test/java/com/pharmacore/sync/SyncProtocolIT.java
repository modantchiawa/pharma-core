package com.pharmacore.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pharmacore.shared.security.ActorResolver;
import com.pharmacore.shared.security.SigningKeyService;
import com.pharmacore.support.IntegrationTest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Plays the hub's side of the protocol against the cloud: pairing with a one-time code, initial
 * snapshot, idempotent push of hub-owned rows, pull of cloud-owned changes only.
 */
class SyncProtocolIT extends IntegrationTest {

    @Test
    void hubPairsThenPushesAndPullsByOwnership() throws Exception {
        var pharmacy = onboardPharmacy("Hub Niamey");
        var product = createProduct("ARTEMETHER LUMEFANTRINE 80/480", "NONE", 2_500);

        // --- pairing: platform issues a code, the hub redeems it with its public key
        String code = read(call(post("/api/platform/tenants/" + pharmacy.tenantId() + "/pairing-codes"), platformToken, null)
                .andExpect(status().isCreated()), "$.code");
        KeyPair keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String kid = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getPublic().getEncoded()), 0, 16);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        UUID keyRowId = UUID.randomUUID();
        mvc.perform(post("/api/public/hub/pairing").contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"%s","nodeId":"hub-test-1","appVersion":"it","keyId":"%s","kid":"%s","publicKeyPem":%s}
                        """.formatted(code, keyRowId, kid, jsonString(pem))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(pharmacy.tenantId().toString()))
                .andExpect(jsonPath("$.slug").value(pharmacy.slug()));
        // a code is single use
        mvc.perform(post("/api/public/hub/pairing").contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"%s","nodeId":"hub-test-2","keyId":"%s","kid":"x","publicKeyPem":%s}
                        """.formatted(code, UUID.randomUUID(), jsonString(pem))))
                .andExpect(status().isUnprocessableContent());

        String hubToken = hubToken(keys, kid, pharmacy.tenantId(), "hub-test-1");
        // the pharmacy is now hub-managed: operational writes through the cloud are refused
        call(post("/api/v1/customers"), pharmacy.ownerToken(), """
                {"displayName":"Refusé","creditLimitCents":0}""")
                .andExpect(status().isConflict());

        // --- snapshot: seeded users, roles, config and chart of accounts come back
        var start = call(get("/api/sync/snapshot?includeHubOwned=true"), hubToken, null).andExpect(status().isOk());
        List<String> tables = JsonPath.read(start.andReturn().getResponse().getContentAsString(), "$.tables");
        assertThat(tables).contains("product", "app_user", "account", "audit_event");
        call(get("/api/sync/snapshot/app_user"), hubToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].email").value(pharmacy.ownerEmail()))
                .andExpect(jsonPath("$.rows[0].tenant_id").value(pharmacy.tenantId().toString()))
                .andExpect(jsonPath("$.hasMore").value(false));
        var accounts = call(get("/api/sync/snapshot/account?limit=5"), hubToken, null).andExpect(status().isOk());
        assertThat(read(accounts, "$.hasMore")).isEqualTo("true");
        long startXid = Long.parseLong(read(start, "$.xid"));

        // --- push: a customer created on the hub lands in the cloud replica, exactly once
        UUID customerId = UUID.randomUUID();
        String customerRow = """
                {"id":"%s","tenant_id":"%s","code":"C000001","display_name":"Mme Hadiza","phone":"+22790000000",
                 "email":null,"address":null,"credit_limit_cents":50000,"credit_balance_cents":0,"loyalty_points":0,
                 "notes":null,"active":true,"created_at":"2026-10-24T08:00:00Z","version":0}
                """.formatted(customerId, pharmacy.tenantId());
        String batch = """
                {"nodeId":"hub-test-1","appVersion":"it","changes":[
                  {"xid":100,"seq":1,"tenantId":"%s","table":"customer","op":"I","pk":{"id":"%s"},"row":%s},
                  {"xid":100,"seq":2,"tenantId":"%s","table":"customer","op":"U","pk":{"id":"%s"},"row":%s}
                ]}""".formatted(pharmacy.tenantId(), customerId, customerRow, pharmacy.tenantId(), customerId,
                customerRow.replace("\"loyalty_points\":0", "\"loyalty_points\":12"));
        call(post("/api/sync/push"), hubToken, batch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.acceptedXid").value(100))
                .andExpect(jsonPath("$.acceptedSeq").value(2));
        call(post("/api/sync/push"), hubToken, batch)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.skipped").value(2));
        call(get("/api/v1/customers/" + customerId), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Mme Hadiza"))
                .andExpect(jsonPath("$.loyaltyPoints").value(12));
        // another pharmacy's row is ignored, never written
        var other = onboardPharmacy("Autre");
        call(post("/api/sync/push"), hubToken, """
                {"nodeId":"hub-test-1","changes":[{"xid":101,"seq":3,"tenantId":"%s","table":"customer","op":"I",
                 "pk":{"id":"%s"},"row":%s}]}""".formatted(other.tenantId(), UUID.randomUUID(),
                customerRow.replace(pharmacy.tenantId().toString(), other.tenantId().toString()).replace(customerId.toString(), UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));

        // --- pull: a catalog change made on the cloud reaches the hub; the hub's own rows do not
        call(put("/api/platform/catalog/products/" + read(call(get("/api/v1/catalog/products?q=ARTEMETHER"), pharmacy.ownerToken(), null), "$[0].cip13")),
                platformToken, """
                {"designation":"ARTEMETHER LUMEFANTRINE 80/480 CPR","listing":"NONE","vatRate":0,"publicPriceCents":2600,"active":true}
                """).andExpect(status().isOk());
        var pull = call(get("/api/sync/pull?afterXid=" + startXid + "&afterSeq=0&limit=500"), hubToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetRequired").value(false));
        String body = pull.andReturn().getResponse().getContentAsString();
        List<String> pulledTables = JsonPath.read(body, "$.changes[*].table");
        assertThat(pulledTables).contains("product").doesNotContain("customer", "app_user");
        List<String> productIds = JsonPath.read(body, "$.changes[?(@.table == 'product')].row.id");
        assertThat(productIds).contains(product.toString());
        List<String> origins = JsonPath.read(body, "$.changes[?(@.table == 'audit_event')].row.origin");
        assertThat(origins).isNotEmpty().allMatch("CLOUD"::equals);

        // a user token cannot call the sync API
        call(get("/api/sync/pull"), pharmacy.ownerToken(), null).andExpect(status().isForbidden());
    }

    private static String hubToken(KeyPair keys, String kid, UUID tenant, String nodeId) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
                new JWTClaimsSet.Builder()
                        .issuer(SigningKeyService.issuerFor(new com.pharmacore.shared.context.TenantId(tenant)))
                        .subject("hub:" + nodeId)
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim(ActorResolver.TENANT_CLAIM, tenant.toString())
                        .claim(ActorResolver.TYPE_CLAIM, ActorResolver.TYPE_HUB_NODE)
                        .claim(ActorResolver.NODE_CLAIM, nodeId)
                        .build());
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    static Map<String, Object> unused() {
        return Map.of();
    }
}
