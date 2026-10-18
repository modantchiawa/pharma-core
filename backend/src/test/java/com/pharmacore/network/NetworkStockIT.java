package com.pharmacore.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pharmacore.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkStockIT extends IntegrationTest {

    @Test
    void partnersSeeCappedAvailabilityWithoutAnyFinancialData() throws Exception {
        var requester = onboardPharmacy("Requester");
        var provider = onboardPharmacy("Provider");
        var outsider = onboardPharmacy("Outsider");
        var product = createProduct("VENTOLINE 100UG SUSP INH", "LIST_I", 412);
        receiveLot(provider, product, "V-1", "2028-09-30", 50);
        receiveLot(outsider, product, "O-1", "2028-09-30", 50);
        UUID network = createNetwork(requester, provider);

        var answer = call(get("/api/v1/network/stock/" + product), requester.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tenantId").value(provider.tenantId().toString()))
                .andExpect(jsonPath("$[0].level").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].quantity").value(10))
                .andExpect(jsonPath("$[0].capped").value(true));
        String body = answer.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Price", "price", "cost", "Cost", "lot", "V-1");

        // Not a member of the network: sees nobody
        call(get("/api/v1/network/stock/" + product), outsider.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        assertThat(network).isNotNull();
    }

    @Test
    void reservationWorkflowHoldsAndReleasesProviderStock() throws Exception {
        var requester = onboardPharmacy("Nord");
        var provider = onboardPharmacy("Sud");
        var product = createProduct("INSULINE ASPART 100UI", "LIST_I", 3_100);
        receiveLot(provider, product, "INS-1", "2027-11-30", 6);
        UUID network = createNetwork(requester, provider);

        var transfer = call(post("/api/v1/network/transfers"), requester.ownerToken(), """
                {"networkId":"%s","providerTenantId":"%s","productId":"%s","quantity":4,"note":"Patient urgent"}
                """.formatted(network, provider.tenantId(), product))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        String transferId = read(transfer, "$.id");

        // Only the provider may accept
        call(post("/api/v1/network/transfers/" + transferId + "/acceptance"), requester.ownerToken(), "{}")
                .andExpect(status().isUnprocessableContent());
        call(post("/api/v1/network/transfers/" + transferId + "/acceptance"), provider.ownerToken(),
                "{\"note\":\"Disponible jusqu'à 18h\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.reservationHeld").value(true));
        assertThat(available(provider, product)).isEqualTo(2);

        // Requester cancels: the provider-side reservation is released in the provider's context
        call(post("/api/v1/network/transfers/" + transferId + "/cancellation"), requester.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(available(provider, product)).isEqualTo(6);

        // A completed transfer withdraws FEFO stock at the provider
        String second = read(call(post("/api/v1/network/transfers"), requester.ownerToken(), """
                {"networkId":"%s","providerTenantId":"%s","productId":"%s","quantity":1}
                """.formatted(network, provider.tenantId(), product)).andExpect(status().isCreated()), "$.id");
        call(post("/api/v1/network/transfers/" + second + "/acceptance"), provider.ownerToken(), "{}")
                .andExpect(status().isOk());
        call(post("/api/v1/network/transfers/" + second + "/completion"), provider.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(available(provider, product)).isEqualTo(5);
    }

    @Test
    void guardRosterIsSharedButOnlyTheCoordinatorEditsIt() throws Exception {
        var coordinator = onboardPharmacy("Coordinatrice");
        var member = onboardPharmacy("Membre");
        UUID network = createNetwork(coordinator, member);
        String slot = """
                {"networkId":"%s","pharmacyTenantId":"%s","type":"NIGHT",
                 "startsAt":"2026-12-24T19:00:00Z","endsAt":"2026-12-25T08:00:00Z"}
                """.formatted(network, member.tenantId());

        call(post("/api/v1/guards"), member.ownerToken(), slot).andExpect(status().isUnprocessableContent());
        call(post("/api/v1/guards"), coordinator.ownerToken(), slot)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pharmacyName").value("Membre"));
        // Same pharmacy, overlapping slot: rejected by the exclusion constraint
        call(post("/api/v1/guards"), coordinator.ownerToken(), slot).andExpect(status().isConflict());

        call(get("/api/v1/guards?from=2026-12-24T00:00:00Z&to=2026-12-26T00:00:00Z"), member.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("NIGHT"));
    }

    private UUID createNetwork(Pharmacy coordinator, Pharmacy... members) throws Exception {
        var ids = new StringBuilder("\"" + coordinator.tenantId() + "\"");
        for (Pharmacy member : members) {
            ids.append(",\"").append(member.tenantId()).append('"');
        }
        var result = call(post("/api/platform/networks"), platformToken, """
                {"name":"Réseau %s","region":"Île-de-France","coordinatorTenantId":"%s","quantityCap":10,
                 "memberTenantIds":[%s]}
                """.formatted(shortId(), coordinator.tenantId(), ids))
                .andExpect(status().isCreated());
        return UUID.fromString(read(result, "$.networkId"));
    }
}
