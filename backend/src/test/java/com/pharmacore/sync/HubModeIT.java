package com.pharmacore.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import com.pharmacore.support.TestJwtIssuer;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Boots the application as a pharmacy hub on a real port and pairs it with "the cloud", which
 * is the same instance (every node carries both halves of the protocol). Exercises the
 * first-run wizard, the background bootstrap, and offline-style local authentication.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(HubModeIT.TestSecurity.class)
class HubModeIT {

    private static final int PORT = freePort();
    private static final Path KEYS = tempDir();
    private static final TestJwtIssuer JWT = IntegrationTest.JWT;

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("pharmacore.node.mode", () -> "HUB");
        registry.add("pharmacore.node.keys-dir", KEYS::toString);
        registry.add("pharmacore.node.cloud-url", () -> "http://localhost:" + PORT);
        registry.add("pharmacore.node.sync-interval", () -> "PT1H");
        registry.add("spring.datasource.url", IntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTest.POSTGRES::getPassword);
        registry.add("spring.flyway.url", IntegrationTest.POSTGRES::getJdbcUrl);
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

    @Test
    void unpairedHubPairsBootstrapsAndAuthenticatesLocally() throws Exception {
        RestClient client = builder.clone().baseUrl("http://localhost:" + PORT).build();
        String platformToken = JWT.platformOperator("ops-hub");

        Map<String, Object> info = client.get().uri("/api/public/hub/info").retrieve().body(Map.class);
        assertThat(info).containsEntry("mode", "HUB").containsEntry("paired", false);
        assertThat((String) info.get("nodeId")).startsWith("hub-");

        // The pharmacy is created on the cloud side (same instance) and gets a pairing code
        String tenantJson = client.post().uri("/api/platform/tenants")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"slug":"hub-it-%s","legalName":"Pharmacie du Sahel","displayName":"Pharmacie du Sahel",
                         "countryCode":"NE","currency":"XOF","timeZone":"Africa/Niamey","subscriptionPlan":"PRO",
                         "ownerEmail":"titulaire-%s@example.org","ownerDisplayName":"Dr Sahel"}
                        """.formatted(suffix(), suffix()))
                .retrieve().body(String.class);
        String tenantId = JsonPath.read(tenantJson, "$.id");
        String ownerEmail = "titulaire-" + suffix() + "@example.org";
        String temporary = JsonPath.read(tenantJson, "$.ownerTemporaryPassword");
        String code = JsonPath.read(client.post().uri("/api/platform/tenants/" + tenantId + "/pairing-codes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken)
                .retrieve().body(String.class), "$.code");

        // Local login is impossible before pairing: the hub serves no pharmacy yet
        var refused = client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"email":"%s","password":"%s","terminalId":"T-HUB"}""".formatted(ownerEmail, temporary))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(refused.is4xxClientError() || refused.is5xxServerError()).isTrue();

        // First-run wizard
        String paired = client.post().uri("/api/public/hub/pair").contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"" + code + "\"}")
                .retrieve().body(String.class);
        assertThat((String) JsonPath.read(paired, "$.displayName")).isEqualTo("Pharmacie du Sahel");
        info = client.get().uri("/api/public/hub/info").retrieve().body(Map.class);
        assertThat(info).containsEntry("paired", true).containsEntry("pharmacySlug", "hub-it-" + suffix());

        // Offline-capable login: password, then PIN switch on the bound terminal
        String login = client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"email":"%s","password":"%s","terminalId":"T-HUB"}""".formatted(ownerEmail, temporary))
                .retrieve().body(String.class);
        String accessToken = JsonPath.read(login, "$.accessToken");
        String terminalToken = JsonPath.read(login, "$.terminalToken");
        assertThat((Boolean) JsonPath.read(login, "$.mustChangePassword")).isTrue();
        List<String> roles = JsonPath.read(login, "$.profile.roles");
        assertThat(roles).containsExactly("TITULAIRE");

        client.put().uri("/api/v1/me/password").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"Sahel2026ok\"}")
                .retrieve().toBodilessEntity();
        String login2 = client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"email":"%s","password":"Sahel2026ok","terminalId":"T-HUB"}""".formatted(ownerEmail))
                .retrieve().body(String.class);
        accessToken = JsonPath.read(login2, "$.accessToken");
        client.put().uri("/api/v1/me/pin").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"password\":\"Sahel2026ok\",\"pin\":\"2580\"}")
                .retrieve().toBodilessEntity();
        String pinLogin = client.post().uri("/api/auth/pin").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"terminalToken":"%s","email":"%s","pin":"2580","terminalId":"T-HUB"}"""
                        .formatted(terminalToken, ownerEmail))
                .retrieve().body(String.class);
        assertThat((String) JsonPath.read(pinLogin, "$.profile.displayName")).isEqualTo("Dr Sahel");
        var wrongPin = client.post().uri("/api/auth/pin").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"terminalToken":"%s","email":"%s","pin":"0000","terminalId":"T-HUB"}"""
                        .formatted(terminalToken, ownerEmail))
                .exchange((request, response) -> response.getStatusCode());
        assertThat(wrongPin).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // The background bootstrap completes and the hub reports itself paired and in sync
        String finalToken = accessToken;
        Map<String, Object> syncStatus = null;
        for (int i = 0; i < 60; i++) {
            syncStatus = client.get().uri("/api/v1/hub/sync").header(HttpHeaders.AUTHORIZATION, "Bearer " + finalToken)
                    .retrieve().body(Map.class);
            if (Boolean.FALSE.equals(syncStatus.get("bootstrapping")) && syncStatus.get("lastPullAt") != null) {
                break;
            }
            Thread.sleep(Duration.ofMillis(500));
        }
        assertThat(syncStatus).containsEntry("paired", true).containsEntry("cloudReachable", true);
        assertThat(syncStatus.get("lastError")).isNull();
    }

    private static String suffix() {
        return Integer.toHexString(PORT);
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
            return Files.createTempDirectory("pharmacore-hub-keys");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
