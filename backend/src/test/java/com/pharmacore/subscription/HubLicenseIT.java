package com.pharmacore.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.subscription.application.HubLicenseService;
import com.pharmacore.subscription.application.LicenseIssuer;
import com.pharmacore.subscription.domain.LicenseClaims;
import com.pharmacore.subscription.domain.LicenseCodec;
import com.pharmacore.subscription.domain.LicenseKeys;
import com.pharmacore.subscription.domain.LicenseRejectedException;
import com.pharmacore.support.IntegrationTest;
import com.pharmacore.support.TestJwtIssuer;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * A real hub (HUB mode on a port, its own database) paired with "the cloud", which is the same
 * instance. The hub pins the cloud's license key at pairing, then enforces what the signed
 * license says on its own: a suspension recorded on the cloud reaches it at the next
 * synchronization, forged, foreign and older licenses are ignored, and a key rotation endorsed
 * by the pinned key is followed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(HubLicenseIT.TestSecurity.class)
class HubLicenseIT {

    private static final int PORT = freePort();
    private static final Path KEYS = tempDir();
    private static final TestJwtIssuer JWT = IntegrationTest.JWT;
    private static final String DATABASE = "hub_license";

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        String url = createDatabase();
        registry.add("server.port", () -> PORT);
        registry.add("pharmacore.node.mode", () -> "HUB");
        registry.add("pharmacore.node.keys-dir", KEYS::toString);
        registry.add("pharmacore.node.cloud-url", () -> "http://localhost:" + PORT);
        registry.add("pharmacore.node.sync-interval", () -> "PT1H");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", IntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTest.POSTGRES::getPassword);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", IntegrationTest.POSTGRES::getUsername);
        registry.add("spring.flyway.password", IntegrationTest.POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSecurity {

        @Bean
        TestJwtIssuer testJwtIssuer() {
            return JWT;
        }
    }

    @Autowired
    RestClient.Builder builder;

    @Autowired
    HubLicenseService hubLicense;

    @Autowired
    LicenseIssuer issuer;

    private RestClient client;
    private final String platform = JWT.platformOperator("ops-licence");

    @Test
    void hubEnforcesTheSignedLicenseWithThePinnedKey() throws Exception {
        client = builder.clone().baseUrl("http://localhost:" + PORT).build();
        String tenantId = createPharmacy("hub-licence", "titulaire@hub-licence.test");
        String other = createPharmacy("hub-voisine", "titulaire@hub-voisine.test");
        String code = JsonPath.read(platformPost("/api/platform/tenants/" + tenantId + "/pairing-codes", null), "$.code");

        // pairing: the reply carries the license key (pinned) and the first license
        String paired = client.post().uri("/api/public/hub/pair").contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + code + "\"}").retrieve().body(String.class);
        List<String> pinnedKids = JsonPath.read(paired, "$.licenseKeys[*].kid");
        assertThat(pinnedKids).isNotEmpty();
        String firstLicense = JsonPath.read(paired, "$.license");
        LicenseClaims held = hubLicense.held().orElseThrow().claims();
        assertThat(held.tenantId().toString()).isEqualTo(tenantId);
        assertThat(held.validUntil()).isEqualTo(held.issuedAt().plus(Duration.ofDays(15)));

        String owner = login("titulaire@hub-licence.test", temporaryPassword);
        Map<String, Object> state = get("/api/v1/subscription/state", owner);
        assertThat(state).containsEntry("mode", "HUB");
        assertThat(((Map<?, ?>) state.get("state")).get("status")).isEqualTo("ACTIVE");
        assertThat(((Map<?, ?>) state.get("license")).get("verifiedAt")).isNotNull();
        awaitBootstrap(owner);

        // suspended on the cloud: the hub keeps its license until it synchronizes, then blocks itself
        platformPut("/api/platform/tenants/" + tenantId + "/status", """
                {"status":"SUSPENDED","reason":"Impayé"}""");
        assertThat(post("/api/v1/customers", owner, "{\"displayName\":\"Avant\",\"creditLimitCents\":0}").value())
                .isEqualTo(201);
        assertThat(synchronizeUntil(owner, "SUSPENDED")).isTrue();
        assertThat(post("/api/v1/customers", owner, "{\"displayName\":\"Bloqué\",\"creditLimitCents\":0}").value())
                .isEqualTo(423);
        HttpStatusCode reading = client.get().uri("/api/v1/inventory/stocks").header(HttpHeaders.AUTHORIZATION, "Bearer " + owner)
                .exchange((request, response) -> response.getStatusCode());
        assertThat(reading.value()).isEqualTo(200);
        long suspendedSerial = hubLicense.held().orElseThrow().claims().serial();

        // forged, foreign and older licenses are ignored: the suspension stays in force
        var forger = LicenseKeys.generate();
        LicenseClaims lifted = new LicenseClaims(held.tenantId(), held.slug(), held.plan(), held.maxTerminals(),
                LicenseClaims.Status.ACTIVE, null, null, null, Instant.now(), Instant.now().plus(Duration.ofDays(365)),
                suspendedSerial + 10);
        assertRejected(LicenseCodec.sign(lifted, pinnedKids.getFirst(), forger.getPrivate()),
                LicenseRejectedException.Reason.BAD_SIGNATURE);
        assertRejected(issuer.issue(java.util.UUID.fromString(other)).token(), LicenseRejectedException.Reason.WRONG_TENANT);
        assertRejected(firstLicense, LicenseRejectedException.Reason.SERIAL_REGRESSION);
        assertThat(hubLicense.held().orElseThrow().claims().serial()).isEqualTo(suspendedSerial);
        assertThat(hubLicense.state().reason()).isEqualTo(com.pharmacore.subscription.domain.LicenseState.Reason.SUSPENDED);

        // reactivated, and the cloud rotates its key: the hub follows the endorsed key at the next sync
        platformPut("/api/platform/tenants/" + tenantId + "/status", """
                {"status":"ACTIVE","reason":"Régularisé"}""");
        String rotated = JsonPath.read(platformPost("/api/platform/subscriptions/license-keys/rotation", null), "$.kid");
        assertThat(rotated).isNotIn(pinnedKids);
        post("/api/v1/subscription/refresh", owner, null);
        Map<String, Object> active = get("/api/v1/subscription/state", owner);
        assertThat(((Map<?, ?>) active.get("state")).get("status")).isEqualTo("ACTIVE");
        assertThat(hubLicense.held().orElseThrow().claims().serial()).isGreaterThan(suspendedSerial);
        assertThat(issuer.current(java.util.UUID.fromString(tenantId)).kid()).isEqualTo(rotated);
        assertThat(post("/api/v1/customers", owner, "{\"displayName\":\"Après\",\"creditLimitCents\":0}").value())
                .isEqualTo(201);
    }

    private String temporaryPassword;

    private String createPharmacy(String slug, String email) {
        String json = platformPost("/api/platform/tenants", """
                {"slug":"%s","legalName":"Pharmacie %s","displayName":"Pharmacie %s","countryCode":"NE",
                 "currency":"XOF","timeZone":"Africa/Niamey","subscriptionPlan":"PRO",
                 "ownerEmail":"%s","ownerDisplayName":"Dr %s"}""".formatted(slug, slug, slug, email, slug));
        if (temporaryPassword == null) {
            temporaryPassword = JsonPath.read(json, "$.ownerTemporaryPassword");
        }
        return JsonPath.read(json, "$.id");
    }

    private void assertRejected(String token, LicenseRejectedException.Reason reason) {
        assertThatThrownBy(() -> hubLicense.accept(token)).isInstanceOfSatisfying(LicenseRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private String login(String email, String password) {
        String body = client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"%s\",\"password\":\"%s\",\"terminalId\":\"T-HUB\"}".formatted(email, password))
                .retrieve().body(String.class);
        return JsonPath.read(body, "$.accessToken");
    }

    private void awaitBootstrap(String token) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Map<String, Object> status = get("/api/v1/hub/sync", token);
            if (Boolean.FALSE.equals(status.get("bootstrapping")) && status.get("lastPullAt") != null) {
                return;
            }
            Thread.sleep(Duration.ofMillis(500));
        }
        throw new AssertionError("Bootstrap not finished");
    }

    /** Synchronization rounds started by the pairing may still be running: a round started meanwhile is coalesced. */
    private boolean synchronizeUntil(String token, String reason) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            post("/api/v1/hub/sync/run", token, null);
            Map<?, ?> state = (Map<?, ?>) get("/api/v1/subscription/state", token).get("state");
            if (reason.equals(state.get("reason"))) {
                return true;
            }
            Thread.sleep(Duration.ofMillis(250));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String uri, String token) {
        return client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(Map.class);
    }

    private HttpStatusCode post(String uri, String token, String json) {
        var request = client.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        return request.exchange((req, response) -> response.getStatusCode());
    }

    private String platformPost(String uri, String json) {
        var request = client.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + platform);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        return request.retrieve().body(String.class);
    }

    private void platformPut(String uri, String json) {
        client.put().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + platform)
                .contentType(MediaType.APPLICATION_JSON).body(json).retrieve().toBodilessEntity();
    }

    /** Its own database: the hub's node settings (pairing, pinned keys) must not meet other hubs'. */
    private static String createDatabase() {
        var container = IntegrationTest.POSTGRES;
        try (var connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
                container.getPassword()); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
            statement.execute("CREATE DATABASE " + DATABASE);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return container.getJdbcUrl().replace("/" + container.getDatabaseName() + "?", "/" + DATABASE + "?")
                .replace("/" + container.getDatabaseName(), "/" + DATABASE);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("pharmacore-hub-license-keys");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
