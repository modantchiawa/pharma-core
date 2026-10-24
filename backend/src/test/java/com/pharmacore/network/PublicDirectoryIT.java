package com.pharmacore.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Patient-facing directory and the pharmacist-side "who has it" search. Two pharmacies of the
 * same network in Niamey: one publishes its listing and availability, the other does not.
 */
class PublicDirectoryIT extends IntegrationTest {

    @Test
    void publicDirectoryExposesOnlyWhatPharmaciesAndCoordinatorsChooseToPublish() throws Exception {
        String suffix = shortId();
        var sahel = onboardPharmacy("Sahel " + suffix);
        var zinder = onboardPharmacy("Zinder " + suffix);
        var product = createProduct("AMOXICILLINE 500MG GEL " + suffix, "LIST_I", 1_800);
        var rare = createProduct("INSULINE GLARGINE " + suffix, "LIST_I", 9_500);
        receiveLot(sahel, product, "AMX-1", "2028-06-30", 50);
        receiveLot(sahel, rare, "INS-1", "2027-12-31", 2);
        receiveLot(zinder, product, "AMX-Z", "2028-06-30", 50);
        locate(sahel, "Pharmacie Sahel " + suffix, "Niamey", "+227 90 00 00 01");
        locate(zinder, "Pharmacie Zinder " + suffix, "Niamey", "+227 90 00 00 02");
        UUID network = createNetwork(sahel, zinder);

        // Nothing is public until the pharmacy opts in
        mvc.perform(get("/api/public/directory/pharmacies").param("city", "Niamey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tenantId == '" + sahel.tenantId() + "')]", hasSize(0)));

        call(put("/api/v1/config/public-sharing"), sahel.ownerToken(), """
                {"publicListing":true,"publicAvailability":true,"latitude":13.5137,"longitude":2.1098,
                 "openingHours":"8h-22h","whatsapp":"+227 90 00 00 01"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicAvailability").value(true));
        // Zinder is listed but keeps its stock private
        call(put("/api/v1/config/public-sharing"), zinder.ownerToken(), """
                {"publicListing":true,"publicAvailability":false}""")
                .andExpect(status().isOk());
        // Availability cannot be published without a listing
        call(put("/api/v1/config/public-sharing"), zinder.ownerToken(), """
                {"publicListing":false,"publicAvailability":true}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicAvailability").value(false));
        call(put("/api/v1/config/public-sharing"), zinder.ownerToken(), """
                {"publicListing":true,"publicAvailability":false}""")
                .andExpect(status().isOk());

        var listed = mvc.perform(get("/api/public/directory/pharmacies").param("city", "niamey"))
                .andExpect(status().isOk());
        List<String> names = JsonPath.read(body(listed), "$[*].pharmacyName");
        assertThat(names).contains("Pharmacie Sahel " + suffix, "Pharmacie Zinder " + suffix);
        assertThat(body(listed)).doesNotContain("price", "cost", "quantity");

        // Public availability: level only, Zinder absent because it does not publish its stock
        var availability = mvc.perform(get("/api/public/directory/availability")
                        .param("q", "AMOXICILLINE 500MG GEL " + suffix).param("city", "Niamey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].product.id").value(product.toString()))
                .andExpect(jsonPath("$[0].pharmacies", hasSize(1)))
                .andExpect(jsonPath("$[0].pharmacies[0].tenantId").value(sahel.tenantId().toString()))
                .andExpect(jsonPath("$[0].pharmacies[0].level").value("IN_STOCK"))
                .andExpect(jsonPath("$[0].pharmacies[0].whatsapp").value("+227 90 00 00 01"));
        assertThat(body(availability)).doesNotContain("quantity", "price", "cost", "AMX-1", "lot");
        mvc.perform(get("/api/public/directory/availability").param("q", "INSULINE GLARGINE " + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pharmacies[0].level").value("LOW"));
        mvc.perform(get("/api/public/directory/availability").param("q", "AM"))
                .andExpect(status().isUnprocessableContent());

        // Guards: the coordinator schedules Zinder, nothing is public until the network publishes
        String slot = """
                {"networkId":"%s","pharmacyTenantId":"%s","type":"NIGHT",
                 "startsAt":"2027-01-10T19:00:00Z","endsAt":"2027-01-11T07:00:00Z"}
                """.formatted(network, zinder.tenantId());
        call(post("/api/v1/guards"), sahel.ownerToken(), slot).andExpect(status().isCreated());
        mvc.perform(get("/api/public/directory/guards").param("city", "Niamey").param("at", "2027-01-10T22:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tenantId == '" + zinder.tenantId() + "')]", hasSize(0)));
        call(put("/api/v1/network/" + network + "/guards-publication"), zinder.ownerToken(), "{\"publish\":true}")
                .andExpect(status().isUnprocessableContent());
        call(put("/api/v1/network/" + network + "/guards-publication"), sahel.ownerToken(), "{\"publish\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishGuards").value(true))
                .andExpect(jsonPath("$.coordinator").value(true));
        mvc.perform(get("/api/public/directory/guards").param("city", "Niamey").param("at", "2027-01-10T22:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tenantId == '" + zinder.tenantId() + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.tenantId == '" + zinder.tenantId() + "')].type").value("NIGHT"))
                .andExpect(jsonPath("$[?(@.tenantId == '" + zinder.tenantId() + "')].phone").value("+227 90 00 00 02"));
        var share = mvc.perform(get("/api/public/directory/guards/share-text")
                        .param("city", "Niamey").param("date", "2027-01-10"))
                .andExpect(status().isOk());
        String text = JsonPath.read(body(share), "$.text");
        assertThat(text).startsWith("Pharmacies de garde à Niamey")
                .contains("Pharmacie Zinder " + suffix + ", tél. +227 90 00 00 02 (nuit 20h00-08h00)");

        // Pharmacist side: who has it, what does this partner have, roster to forward
        var who = call(get("/api/v1/network/stock").param("q", "AMOXICILLINE 500MG GEL " + suffix),
                zinder.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].productId").value(product.toString()))
                .andExpect(jsonPath("$[0].partners", hasSize(1)))
                .andExpect(jsonPath("$[0].partners[0].tenantId").value(sahel.tenantId().toString()))
                .andExpect(jsonPath("$[0].partners[0].quantity").value(10))
                .andExpect(jsonPath("$[0].partners[0].capped").value(true));
        assertThat(body(who)).doesNotContain("price", "cost", "AMX-1");
        call(get("/api/v1/network/pharmacies/" + sahel.tenantId() + "/stock").param("q", suffix),
                zinder.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.productId == '" + rare + "')].quantity").value(2))
                .andExpect(jsonPath("$[?(@.productId == '" + rare + "')].level").value("LOW"));
        // Not a partner of itself: nothing comes back
        call(get("/api/v1/network/pharmacies/" + sahel.tenantId() + "/stock").param("q", suffix),
                sahel.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        call(get("/api/v1/guards/share-text").param("networkId", network.toString()).param("date", "2027-01-10"),
                zinder.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots").value(1))
                .andExpect(jsonPath("$.timeZone").value("Africa/Niamey"));
        call(get("/api/v1/network/networks"), zinder.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.networkId == '" + network + "')].publishGuards").value(true))
                .andExpect(jsonPath("$[?(@.networkId == '" + network + "')].coordinator").value(false));

        // No pharmacy right: the platform operator cannot query partners' stock
        call(get("/api/v1/network/stock").param("q", "AMOX"), platformToken, null)
                .andExpect(status().isForbidden());

        // The public directory is throttled per client address
        boolean throttled = false;
        for (int i = 0; i < 40 && !throttled; i++) {
            int code = mvc.perform(get("/api/public/directory/pharmacies").with(request -> {
                        request.setRemoteAddr("203.0.113.7");
                        return request;
                    })).andReturn().getResponse().getStatus();
            throttled = code == 429;
        }
        assertThat(throttled).isTrue();
        // Another client is not affected
        mvc.perform(get("/api/public/directory/pharmacies").header("X-Forwarded-For", "198.51.100.9, 10.0.0.1"))
                .andExpect(status().isOk());
    }

    private void locate(Pharmacy pharmacy, String name, String city, String phone) throws Exception {
        call(put("/api/v1/pharmacy/branding"), pharmacy.ownerToken(), """
                {"displayName":"%s","city":"%s","countryCode":"NE","phone":"%s",
                 "primaryColor":"#0F766E","secondaryColor":"#134E4A","accentColor":"#F59E0B"}
                """.formatted(name, city, phone))
                .andExpect(status().isOk());
    }

    private UUID createNetwork(Pharmacy coordinator, Pharmacy member) throws Exception {
        var result = call(post("/api/platform/networks"), platformToken, """
                {"name":"Réseau %s","region":"Niamey","coordinatorTenantId":"%s","quantityCap":10,
                 "memberTenantIds":["%s","%s"]}
                """.formatted(shortId(), coordinator.tenantId(), coordinator.tenantId(), member.tenantId()))
                .andExpect(status().isCreated());
        return UUID.fromString(read(result, "$.networkId"));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
