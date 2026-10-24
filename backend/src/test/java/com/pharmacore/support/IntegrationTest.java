package com.pharmacore.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.catalog.domain.Cip13;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Full-stack tests on a real PostgreSQL: Flyway runs as the schema owner while the application
 * pool switches to {@code pharmacore_app}, exactly like production, so RLS is really exercised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.TestSecurity.class)
public abstract class IntegrationTest {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    public static final TestJwtIssuer JWT = new TestJwtIssuer();

    static {
        POSTGRES.start();
    }

    private static final java.nio.file.Path KEYS_DIR;

    static {
        try {
            KEYS_DIR = java.nio.file.Files.createTempDirectory("pharmacore-keys");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("pharmacore.node.keys-dir", KEYS_DIR::toString);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mvc;

    protected final String platformToken = JWT.platformOperator("ops-1");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSecurity {

        @Bean
        TestJwtIssuer testJwtIssuer() {
            return JWT;
        }
    }

    // ------------------------------------------------------------------ fixtures

    protected record Pharmacy(UUID tenantId, String slug, String ownerEmail, String ownerPassword, String ownerToken) {
    }

    protected Pharmacy onboardPharmacy(String name) throws Exception {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + shortId();
        String email = "owner-" + slug + "@example.org";
        var result = call(post("/api/platform/tenants"), platformToken, """
                {"slug":"%s","legalName":"SARL %s","displayName":"%s","countryCode":"NE","currency":"XOF",
                 "timeZone":"Africa/Niamey","subscriptionPlan":"PRO",
                 "ownerEmail":"%s","ownerDisplayName":"Dr %s"}
                """.formatted(slug, name, name, email, name))
                .andExpect(status().isCreated());
        UUID tenantId = UUID.fromString(read(result, "$.id"));
        String temporary = read(result, "$.ownerTemporaryPassword");
        return new Pharmacy(tenantId, slug, email, temporary, login(slug, email, temporary));
    }

    /** Local (offline-capable) authentication, exactly as the PWA does it. */
    protected String login(String slug, String email, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"pharmacy":"%s","email":"%s","password":"%s","terminalId":"T-TEST"}
                        """.formatted(slug, email, password)))
                .andExpect(status().isOk());
        return read(result, "$.accessToken");
    }

    protected UUID createProduct(String designation, String listing, long publicPriceCents) throws Exception {
        String first12 = "34009" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        String cip13 = first12 + Cip13.checkDigit(first12);
        var result = call(put("/api/platform/catalog/products/" + cip13), platformToken, """
                {"designation":"%s","dci":"%s","listing":"%s","vatRate":2.10,"publicPriceCents":%d,"active":true}
                """.formatted(designation, designation, listing, publicPriceCents))
                .andExpect(status().isOk());
        return UUID.fromString(read(result, "$.id"));
    }

    protected UUID receiveLot(Pharmacy pharmacy, UUID productId, String lot, String expiry, int quantity)
            throws Exception {
        var result = call(post("/api/v1/inventory/lots"), pharmacy.ownerToken(), """
                {"productId":"%s","lotNumber":"%s","expiryDate":"%s","quantity":%d,"unitCostCents":120}
                """.formatted(productId, lot, expiry, quantity))
                .andExpect(status().isCreated());
        return UUID.fromString(read(result, "$.lotId"));
    }

    protected int available(Pharmacy pharmacy, UUID productId) throws Exception {
        var result = call(get("/api/v1/inventory/stocks"), pharmacy.ownerToken(), null).andExpect(status().isOk());
        String body = result.andReturn().getResponse().getContentAsString();
        java.util.List<Integer> values = JsonPath.read(body, "$[?(@.productId == '" + productId + "')].available");
        return values.isEmpty() ? 0 : values.getFirst();
    }

    // ------------------------------------------------------------------ http helpers

    protected ResultActions call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    protected static String read(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }

    protected static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
