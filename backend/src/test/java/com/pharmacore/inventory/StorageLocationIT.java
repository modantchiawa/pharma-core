package com.pharmacore.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.catalog.domain.Cip13;
import com.pharmacore.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The pharmacist organises the pharmacy into locations, puts products away one by one and in
 * bulk, and every screen that shows the product shows where it is: stock lists, the offline POS
 * feed (re-emitted when a location is renamed) and a count scoped to one location.
 */
class StorageLocationIT extends IntegrationTest {

    @Test
    void locationsAreManagedAssignedAndShownWhereverTheProductIs() throws Exception {
        var pharmacy = onboardPharmacy("Emplacements Tahoua");
        String owner = pharmacy.ownerToken();
        var paracetamol = createProduct("PARACETAMOL 500MG CPR B/16", "NONE", 650);
        var insulin = createProduct("INSULATARD 100UI/ML", "LIST_I", 9_500);
        var vaccine = createProduct("VACCIN ANTITETANIQUE", "LIST_I", 2_500);
        receiveLot(pharmacy, paracetamol, "P-1", "2028-03-31", 30);
        receiveLot(pharmacy, insulin, "I-1", "2027-06-30", 5);
        receiveLot(pharmacy, vaccine, "V-1", "2027-09-30", 8);

        // --- create, normalised code, duplicate refused
        String shelf = read(call(post("/api/v1/inventory/locations"), owner, """
                {"code":" a3 ","name":"Rayon A · étagère 3","kind":"SHELF","sortOrder":10}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("A3"))
                .andExpect(jsonPath("$.productCount").value(0)), "$.id");
        String fridge = read(call(post("/api/v1/inventory/locations"), owner, """
                {"code":"FRIGO","name":"Réfrigérateur","kind":"FRIDGE","sortOrder":20}""")
                .andExpect(status().isCreated()), "$.id");
        call(post("/api/v1/inventory/locations"), owner, """
                {"code":"A3","name":"Doublon","kind":"SHELF","sortOrder":30}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("location-code-taken"));
        call(post("/api/v1/inventory/locations"), owner, """
                {"code":"A3 · bas","name":"Code invalide","kind":"SHELF","sortOrder":30}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid-location-code"));

        // --- one product with a detail, then two in bulk
        call(put("/api/v1/inventory/stocks/" + paracetamol + "/location"), owner, """
                {"locationId":"%s","detail":"  case 12 "}""".formatted(shelf))
                .andExpect(status().isNoContent());
        call(put("/api/v1/inventory/stocks/" + insulin + "/location"), owner, """
                {"locationId":"%s","detail":"bac 4"}""".formatted(shelf))
                .andExpect(status().isNoContent());
        call(post("/api/v1/inventory/stocks/location-assignments"), owner, """
                {"productIds":["%s","%s"],"locationId":"%s"}""".formatted(insulin, vaccine, fridge))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2));

        // --- stock views: location of each product, filters by location and unassigned
        String stocks = body(call(get("/api/v1/inventory/stocks"), owner, null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(stocks, "$[?(@.productId == '" + paracetamol + "')].location.code"))
                .containsExactly("A3");
        assertThat(JsonPath.<List<String>>read(stocks, "$[?(@.productId == '" + paracetamol + "')].location.detail"))
                .containsExactly("case 12");
        assertThat(JsonPath.<List<String>>read(stocks, "$[?(@.productId == '" + insulin + "')].location.kind"))
                .containsExactly("FRIDGE");
        // the detail of the former location does not follow the product
        assertThat(JsonPath.<List<Object>>read(stocks, "$[?(@.productId == '" + insulin + "')].location.detail"))
                .containsOnlyNulls();
        call(get("/api/v1/inventory/stocks?locationId=" + fridge), owner, null)
                .andExpect(jsonPath("$.length()").value(2));
        call(get("/api/v1/inventory/locations"), owner, null)
                .andExpect(jsonPath("$[?(@.code == 'FRIGO')].productCount").value(2))
                .andExpect(jsonPath("$[?(@.code == 'A3')].productCount").value(1));
        call(put("/api/v1/inventory/stocks/" + vaccine + "/location"), owner, """
                {"locationId":null,"detail":"porte"}""")
                .andExpect(status().isNoContent());
        call(get("/api/v1/inventory/stocks?unassigned=true"), owner, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productId").value(vaccine.toString()))
                .andExpect(jsonPath("$[0].location").doesNotExist());

        // --- a count scoped to one location freezes only its lots
        call(post("/api/v1/inventory/counts"), owner, """
                {"label":"Frigo, octobre","locationId":"%s"}""".formatted(fridge))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.location").value("FRIGO · Réfrigérateur"))
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.lines[0].lotNumber").value("I-1"));

        // --- POS offline feed carries the location; renaming it re-emits its products
        Map<String, Object> cursor = drainPosFeed(owner, "1970-01-01T00:00:00Z", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) cursor.get("items");
        assertThat(all).filteredOn(item -> paracetamol.toString().equals(item.get("productId")))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.get("locationCode")).isEqualTo("A3");
                    assertThat(item.get("locationName")).isEqualTo("Rayon A · étagère 3");
                    assertThat(item.get("locationKind")).isEqualTo("SHELF");
                    assertThat(item.get("locationDetail")).isEqualTo("case 12");
                });
        call(put("/api/v1/inventory/locations/" + shelf), owner, """
                {"code":"A3","name":"Rayon A · antalgiques","kind":"SHELF","sortOrder":10}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rayon A · antalgiques"));
        Map<String, Object> delta = drainPosFeed(owner, (String) cursor.get("nextSince"),
                (String) cursor.get("nextAfter"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changed = (List<Map<String, Object>>) delta.get("items");
        assertThat(changed).extracting(item -> item.get("productId")).containsExactly(paracetamol.toString());
        assertThat(changed.getFirst().get("locationName")).isEqualTo("Rayon A · antalgiques");

        // --- deactivation: refused while products are there, accepted when they are reassigned
        call(put("/api/v1/inventory/locations/" + fridge + "/activation"), owner, """
                {"active":false}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("location-not-empty"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("contient encore 1 produit(s)")));
        call(put("/api/v1/inventory/locations/" + fridge + "/activation"), owner, """
                {"active":false,"reassignTo":"%s"}""".formatted(fridge))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("location-reassign-self"));
        String reserve = read(call(post("/api/v1/inventory/locations"), owner, """
                {"code":"FRIGO 2","name":"Réfrigérateur de réserve","kind":"FRIDGE","sortOrder":30}""")
                .andExpect(status().isCreated()), "$.id");
        call(put("/api/v1/inventory/locations/" + fridge + "/activation"), owner, """
                {"active":false,"reassignTo":"%s"}""".formatted(reserve))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.productCount").value(0));
        call(get("/api/v1/inventory/stocks?locationId=" + reserve), owner, null)
                .andExpect(jsonPath("$[0].productId").value(insulin.toString()));
        call(get("/api/v1/inventory/locations"), owner, null)
                .andExpect(jsonPath("$[?(@.code == 'FRIGO')]").isEmpty());
        call(get("/api/v1/inventory/locations?includeInactive=true"), owner, null)
                .andExpect(jsonPath("$[?(@.code == 'FRIGO')].active").value(false));
        call(put("/api/v1/inventory/stocks/" + vaccine + "/location"), owner, """
                {"locationId":"%s"}""".formatted(fridge))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("location-inactive"));
        call(put("/api/v1/inventory/locations/" + fridge + "/activation"), owner, """
                {"active":true}""")
                .andExpect(jsonPath("$.active").value(true));

        // --- a product file with a free-text location: existing code reused, unknown label created
        String serum = cip13();
        String gauze = cip13();
        String file = "Code;Designation;Emplacement\n" + serum + ";SERUM PHYSIOLOGIQUE 5ML;frigo\n"
                + gauze + ";COMPRESSES STERILES;Réserve du fond\n";
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("file", "produits.csv",
                                "text/csv", file.getBytes(StandardCharsets.UTF_8)))
                        .param("kind", "PRODUCTS").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.rejected").value(0));
        call(get("/api/v1/inventory/locations"), owner, null)
                .andExpect(jsonPath("$[?(@.code == 'FRIGO')].productCount").value(1))
                .andExpect(jsonPath("$[?(@.code == 'RESERVE DU FOND')].kind").value("RESERVE"))
                .andExpect(jsonPath("$[?(@.code == 'RESERVE DU FOND')].name").value("Réserve du fond"));

        // --- a cashier reads locations but does not organise them
        String cashier = staff(pharmacy, "CAISSIER", "caissier");
        call(get("/api/v1/inventory/locations"), cashier, null).andExpect(status().isOk());
        call(post("/api/v1/inventory/locations"), cashier, """
                {"code":"B1","name":"Rayon B","kind":"SHELF","sortOrder":40}""")
                .andExpect(status().isForbidden());
        call(put("/api/v1/inventory/stocks/" + vaccine + "/location"), cashier, """
                {"locationId":"%s"}""".formatted(shelf))
                .andExpect(status().isForbidden());

        // --- another pharmacy neither sees nor uses these locations
        var other = onboardPharmacy("Emplacements Maradi");
        call(get("/api/v1/inventory/locations?includeInactive=true"), other.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/inventory/locations/" + shelf), other.ownerToken(), null)
                .andExpect(status().isNotFound());
        call(put("/api/v1/inventory/stocks/" + paracetamol + "/location"), other.ownerToken(), """
                {"locationId":"%s"}""".formatted(shelf))
                .andExpect(status().isNotFound());
        call(put("/api/v1/inventory/locations/" + shelf), other.ownerToken(), """
                {"code":"X","name":"Piratage","kind":"OTHER","sortOrder":0}""")
                .andExpect(status().isNotFound());
        // the same code is free in another pharmacy
        call(post("/api/v1/inventory/locations"), other.ownerToken(), """
                {"code":"A3","name":"Rayon A","kind":"SHELF","sortOrder":10}""")
                .andExpect(status().isCreated());
        String otherFeed = body(call(get("/api/v1/pos/catalog?limit=2000"), other.ownerToken(), null));
        assertThat(JsonPath.<List<Object>>read(otherFeed,
                "$.items[?(@.productId == '" + paracetamol + "')].locationCode")).containsOnlyNulls();
    }

    private static String cip13() {
        String first12 = "34009" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        return first12 + Cip13.checkDigit(first12);
    }

    /** Reads the POS feed from a cursor to its end, like the terminal does. */
    private Map<String, Object> drainPosFeed(String token, String since, String after) throws Exception {
        List<Object> items = new ArrayList<>();
        String nextSince = since;
        String nextAfter = after;
        boolean more = true;
        while (more) {
            String url = "/api/v1/pos/catalog?limit=2000&since=" + nextSince
                    + (nextAfter == null ? "" : "&after=" + nextAfter);
            String page = body(call(get(url), token, null).andExpect(status().isOk()));
            items.addAll(JsonPath.<List<Object>>read(page, "$.items"));
            nextSince = JsonPath.read(page, "$.nextSince");
            nextAfter = JsonPath.read(page, "$.nextAfter");
            more = JsonPath.read(page, "$.hasMore");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("nextSince", nextSince);
        result.put("nextAfter", nextAfter);
        return result;
    }

    private String staff(Pharmacy pharmacy, String roleCode, String name) throws Exception {
        String roles = body(call(get("/api/v1/roles"), pharmacy.ownerToken(), null).andExpect(status().isOk()));
        String roleId = JsonPath.<List<String>>read(roles, "$[?(@.code == '" + roleCode + "')].id").getFirst();
        String email = name + "-" + pharmacy.slug() + "@example.org";
        var registered = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"%s","roleIds":["%s"]}""".formatted(email, name, roleId))
                .andExpect(status().isCreated());
        return login(pharmacy.slug(), email, read(registered, "$.temporaryPassword"));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
