package com.pharmacore.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.support.IntegrationTest;
import com.pharmacore.sync.ChangeApplier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The pharmacist photographs a product: every view that lets the staff pick the product carries
 * the photo's version (POS feed, search, stock list, expiry alerts, order lines, product sheet),
 * the image is served with an ETag and cached for good under its version, the removal is
 * replicated as an update, and nothing of it is visible to another pharmacy.
 */
class ProductImageIT extends IntegrationTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ChangeApplier applier;

    @Autowired
    ObjectMapper json;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void photoIsStoredServedListedReplicatedAndIsolated() throws Exception {
        var pharmacy = onboardPharmacy("Miniatures Zinder");
        var other = onboardPharmacy("Miniatures Agadez");
        String owner = pharmacy.ownerToken();
        var product = createProduct("AMOXICILLINE 500MG GEL B/12", "LIST_I", 1_750);
        receiveLot(pharmacy, product, "A-1", "2027-12-31", 3);
        receiveLot(other, product, "B-1", "2027-12-31", 9);

        // --- upload: the bytes decide the type, the version is the start of the content hash
        String version = read(upload(owner, product, PNG, "boite.jpg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.sizeBytes").value(PNG.length)), "$.imageVersion");
        assertThat(version).hasSize(16).isEqualTo(sha256(PNG).substring(0, 16));
        upload(owner, product, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8), "x.png")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("product-image-format"));
        upload(owner, product, new byte[300 * 1024], "big.jpg")
                .andExpect(status().isUnprocessableContent());
        upload(owner, UUID.randomUUID(), PNG, "ghost.png").andExpect(status().isNotFound());

        // --- served with its hash as ETag; immutable under its version, revalidated otherwise
        String etag = "\"" + sha256(PNG) + "\"";
        var served = call(get(imageUrl(product) + "?v=" + version), owner, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("ETag", etag))
                .andExpect(header().string("Cache-Control", "max-age=31536000, private, immutable"))
                .andReturn().getResponse();
        assertThat(served.getContentAsByteArray()).isEqualTo(PNG);
        call(get(imageUrl(product) + "?v=0000000000000000"), owner, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache, private"));
        call(get(imageUrl(product)).header("If-None-Match", etag), owner, null)
                .andExpect(status().isNotModified());

        // --- every product view used to pick the product carries the version
        String stocks = body(call(get("/api/v1/inventory/stocks"), owner, null));
        assertThat(JsonPath.<List<String>>read(stocks, "$[?(@.productId == '" + product + "')].imageVersion"))
                .containsExactly(version);
        String feed = body(call(get("/api/v1/pos/catalog"), owner, null));
        assertThat(JsonPath.<List<String>>read(feed, "$.items[?(@.productId == '" + product + "')].imageVersion"))
                .containsExactly(version);
        String search = body(call(get("/api/v1/catalog/products?q=AMOXICILLINE 500MG"), owner, null));
        assertThat(JsonPath.<List<String>>read(search, "$[?(@.id == '" + product + "')].imageVersion"))
                .containsExactly(version);
        call(get("/api/v1/inventory/products/" + product), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageVersion").value(version))
                .andExpect(jsonPath("$.imageSizeBytes").value(PNG.length))
                .andExpect(jsonPath("$.onHand").value(3))
                .andExpect(jsonPath("$.lots[0].lotNumber").value("A-1"));
        receiveLot(pharmacy, product, "A-2", java.time.LocalDate.now().plusDays(20).toString(), 2);
        String alerts = body(call(get("/api/v1/inventory/expiry-alerts?withinDays=60"), owner, null));
        assertThat(JsonPath.<List<String>>read(alerts, "$[?(@.lotNumber == 'A-2')].imageVersion"))
                .containsExactly(version);
        String supplier = read(call(post("/api/v1/procurement/suppliers"), owner, """
                {"name":"Laborex","leadTimeDays":1,"minOrderCents":0}""").andExpect(status().isCreated()), "$.id");
        call(post("/api/v1/procurement/orders"), owner, """
                {"supplierId":"%s","lines":[{"productId":"%s","quantity":10,"unitCostCents":1200}]}"""
                .formatted(supplier, product))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].imageVersion").value(version));

        // --- a cashier sees the photo but cannot change it
        String cashier = cashier(pharmacy);
        call(get(imageUrl(product)), cashier, null).andExpect(status().isOk());
        upload(cashier, product, PNG, "boite.png").andExpect(status().isForbidden());
        call(delete(imageUrl(product)), cashier, null).andExpect(status().isForbidden());

        // --- isolation: the other pharmacy has no photo of the same product and sees no row
        call(get(imageUrl(product)), other.ownerToken(), null).andExpect(status().isNotFound());
        String otherStocks = body(call(get("/api/v1/inventory/stocks"), other.ownerToken(), null));
        assertThat(JsonPath.<List<Object>>read(otherStocks, "$[?(@.productId == '" + product + "')].imageVersion"))
                .containsExactly((Object) null);
        assertThat(countImages(other.tenantId())).isZero();
        assertThat(countImages(pharmacy.tenantId())).isEqualTo(1);

        // --- replication: the captured change carries the bytes and replays them identically
        JsonNode captured = lastCapturedRow(pharmacy.tenantId());
        assertThat(captured.get("content").asString()).isEqualTo("\\x" + HexFormat.of().formatHex(PNG));
        inTenant(pharmacy.tenantId(), () -> {
            jdbc.sql("SELECT set_config('app.sync_apply', 'on', true)").query(String.class).single();
            jdbc.sql("DELETE FROM product_image WHERE product_id = :product").param("product", product).update();
            applier.upsertAll("product_image", List.of(captured));
            return null;
        });
        assertThat(call(get(imageUrl(product)), owner, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).isEqualTo(PNG);

        // --- removal keeps the row empty: replicated as an update, product re-emitted to the POS
        call(delete(imageUrl(product)), owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageVersion").doesNotExist());
        call(get(imageUrl(product)), owner, null).andExpect(status().isNotFound());
        call(delete(imageUrl(product)), owner, null).andExpect(status().isNotFound());
        JsonNode removed = lastCapturedRow(pharmacy.tenantId());
        assertThat(removed.get("content").isNull()).isTrue();
        String since = JsonPath.read(feed, "$.nextSince");
        String delta = body(call(get("/api/v1/pos/catalog?since=" + since), owner, null));
        assertThat(JsonPath.<List<Object>>read(delta, "$.items[?(@.productId == '" + product + "')].imageVersion"))
                .containsExactly((Object) null);
        assertThat(auditActions(pharmacy.tenantId(), product))
                .containsSubsequence("PRODUCT_IMAGE_UPDATED", "PRODUCT_IMAGE_REMOVED");
    }

    private ResultActions upload(String token, UUID product, byte[] content, String name) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, imageUrl(product))
                .file(new MockMultipartFile("file", name, "image/jpeg", content))
                .header("Authorization", "Bearer " + token));
    }

    private static String imageUrl(UUID product) {
        return "/api/v1/inventory/products/" + product + "/image";
    }

    private String cashier(Pharmacy pharmacy) throws Exception {
        String role = JsonPath.<List<String>>read(body(call(get("/api/v1/roles"), pharmacy.ownerToken(), null)),
                "$[?(@.code == 'CAISSIER')].id").getFirst();
        String email = "cashier-" + pharmacy.slug() + "@example.org";
        var registration = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"Caissier","roleIds":["%s"]}""".formatted(email, role))
                .andExpect(status().isCreated());
        return login(pharmacy.slug(), email, read(registration, "$.temporaryPassword"));
    }

    private long countImages(UUID tenant) {
        return inTenant(tenant, () -> jdbc.sql("SELECT count(*) FROM product_image").query(Long.class).single());
    }

    private JsonNode lastCapturedRow(UUID tenant) {
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("test-sync"));
        String row = TenantContext.call(context, () -> new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("""
                                SELECT row::text FROM sync_change
                                WHERE tenant_id = :tenant AND table_name = 'product_image'
                                ORDER BY seq DESC LIMIT 1""")
                        .param("tenant", tenant)
                        .query(String.class)
                        .single()));
        return json.readTree(row);
    }

    private List<String> auditActions(UUID tenant, UUID product) {
        return inTenant(tenant, () -> jdbc.sql("""
                        SELECT action FROM audit_event WHERE resource_type = 'PRODUCT' AND resource_id = :product
                        ORDER BY sequence_no""")
                .param("product", product.toString())
                .query(String.class)
                .list());
    }

    private <T> T inTenant(UUID tenant, java.util.function.Supplier<T> action) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test", new TenantId(tenant)));
        return TenantContext.call(context, () -> new TransactionTemplate(transactionManager)
                .execute(status -> action.get()));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }
}
