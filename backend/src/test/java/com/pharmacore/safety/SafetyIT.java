package com.pharmacore.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.catalog.domain.Cip13;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SafetyIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void seededReferentialIsServedToTerminalsAndMaintainedByThePlatform() throws Exception {
        Pharmacy pharmacy = onboardPharmacy("Pharmacie Vigilance");

        String feed = body(call(get("/api/v1/safety/rules?since=1970-01-01T00:00:00Z&limit=50"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items.length()").value(50)));
        assertThat(JsonPath.<List<String>>read(feed, "$.items[*].rule.kind")).containsAnyOf("INTERACTION", "CONTEXT");

        call(put("/api/platform/safety/rules"), platformToken, """
                {"source":"Thésaurus test","rules":[
                  {"kind":"INTERACTION","substance":"Warfarine","counterpart":"acide acétylsalicylique",
                   "severity":"CONTRAINDICATED","advice":"Règle de test."},
                  {"kind":"CONTEXT","substance":"ATC:J01M","counterpart":"EPILEPSY","severity":"PRECAUTION",
                   "advice":"Abaisse le seuil épileptogène."}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(2));
        // the same natural key is refreshed, not duplicated, whatever the order of the substances
        call(put("/api/platform/safety/rules"), platformToken, """
                {"rules":[{"kind":"INTERACTION","substance":"ACIDE ACETYLSALICYLIQUE","counterpart":"WARFARINE",
                           "severity":"ADVISED_AGAINST","advice":"Règle de test corrigée."}]}""")
                .andExpect(status().isOk());
        long duplicates = inTenant(pharmacy, () -> new TransactionTemplate(transactionManager).execute(status -> jdbc.sql("""
                        SELECT count(*) FROM drug_interaction
                        WHERE substance = 'ACIDE ACETYLSALICYLIQUE' AND counterpart = 'WARFARINE'""")
                .query(Long.class).single()));
        assertThat(duplicates).isEqualTo(1);

        // a member of a pharmacy cannot write the referential
        call(put("/api/platform/safety/rules"), pharmacy.ownerToken(), """
                {"rules":[{"kind":"INTERACTION","substance":"A","counterpart":"B","severity":"PRECAUTION","advice":"x"}]}""")
                .andExpect(status().isForbidden());
    }

    @Test
    void counterIsWarnedAndAContraindicationNeedsThePharmacistsOverride() throws Exception {
        Pharmacy pharmacy = onboardPharmacy("Pharmacie du Comptoir");
        UUID augmentin = createDrug("AUGMENTIN 1 g", "Amoxicilline / acide clavulanique", "J01CR02");
        UUID warfarine = createDrug("COUMADINE 5 mg", "Warfarine", "B01AA03");
        UUID daktarin = createDrug("DAKTARIN gel buccal", "Miconazole", "A01AB09");
        UUID doliprane = createDrug("DOLIPRANE 1000", "Paracétamol", "N02BE01");
        for (UUID product : List.of(augmentin, warfarine, daktarin, doliprane)) {
            receiveLot(pharmacy, product, "L-1", LocalDate.now().plusYears(1).toString(), 20);
        }
        String customerId = read(call(post("/api/v1/customers"), pharmacy.ownerToken(), """
                {"displayName":"Hadiza Moussa","phone":"+22790222222","creditLimitCents":0}""")
                .andExpect(status().isCreated()), "$.id");

        // no file yet: an empty profile, and the seeded interaction still fires
        call(get("/api/v1/customers/" + customerId + "/health"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empty").value(true));
        call(post("/api/v1/safety/check"), pharmacy.ownerToken(), """
                {"customerId":"%s","productIds":["%s","%s"]}""".formatted(customerId, warfarine, daktarin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileKnown").value(false))
                .andExpect(jsonPath("$.blocking").value(1))
                .andExpect(jsonPath("$.alerts[0].kind").value("INTERACTION"))
                .andExpect(jsonPath("$.alerts[0].severity").value("CONTRAINDICATED"))
                .andExpect(jsonPath("$.alerts[0].title").value("Interaction : COUMADINE 5 mg + DAKTARIN gel buccal"));

        // the pharmacist records what they know of the patient
        call(put("/api/v1/customers/" + customerId + "/health"), pharmacy.ownerToken(), """
                {"birthDate":"%s","sex":"F","pregnant":true,"breastfeeding":false,
                 "allergies":["PENICILLINS","sulfamides","iode"],"conditions":["HEPATIC","PREGNANCY"],"notes":"Suivi CSI"}"""
                .formatted(LocalDate.now().minusYears(30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ageYears").value(30))
                .andExpect(jsonPath("$.allergies.length()").value(3))
                .andExpect(jsonPath("$.allergies[0]").value("PENICILLINS"))
                .andExpect(jsonPath("$.allergies[1]").value("SULFONAMIDES"))
                // PREGNANCY is a state of the file, not a declared condition
                .andExpect(jsonPath("$.conditions").value(List.of("HEPATIC")));

        String check = body(call(post("/api/v1/safety/check"), pharmacy.ownerToken(), """
                {"customerId":"%s","productIds":["%s","%s","%s"]}""".formatted(customerId, augmentin, warfarine, doliprane))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileKnown").value(true)));
        List<String> kinds = JsonPath.read(check, "$.alerts[*].kind");
        List<String> titles = JsonPath.read(check, "$.alerts[*].title");
        assertThat(kinds).contains("ALLERGY", "CONTEXT");
        assertThat(titles).contains("Allergie déclarée (Pénicillines) : AUGMENTIN 1 g", "Grossesse : COUMADINE 5 mg",
                "Insuffisance hépatique : DOLIPRANE 1000");
        String allergyKey = JsonPath.<List<String>>read(check, "$.alerts[?(@.kind == 'ALLERGY')].key").getFirst();
        String pregnancyKey = JsonPath.<List<String>>read(check, "$.alerts[?(@.title == 'Grossesse : COUMADINE 5 mg')].key")
                .getFirst();

        // online, the contraindicated basket is refused without an override
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(List.of(augmentin, warfarine), 1_000, "[]"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.violations").value(org.hamcrest.Matchers.hasItem("SAFETY_CONTRAINDICATION")));

        // the owner (SAFETY_OVERRIDE) takes the responsibility, with a reason for each alert
        String saleId = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(List.of(augmentin, warfarine), 1_000, """
                [{"alertKey":"%s","reason":"Allergie infirmée par le patient, prescription hospitalière"},
                 {"alertKey":"%s","reason":"Relais héparine impossible, décision du gynécologue"}]"""
                .formatted(allergyKey, pregnancyKey)))
                .andExpect(status().isCreated()), "$.saleId");

        List<String> recorded = inTenant(pharmacy, () -> new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT kind FROM dispensing_safety_override WHERE sale_id = :sale ORDER BY kind")
                        .param("sale", UUID.fromString(saleId)).query(String.class).list()));
        assertThat(recorded).containsExactly("ALLERGY", "CONTEXT");
        call(get("/api/v1/control/alerts"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[?(@.kind == 'SAFETY_OVERRIDE')].length()").value(2))
                .andExpect(jsonPath("$.alerts[0].detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Hadiza"))));

        // the same product sold again today is a precaution, not a block
        call(post("/api/v1/safety/check"), pharmacy.ownerToken(), """
                {"customerId":"%s","productIds":["%s"]}""".formatted(customerId, augmentin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[?(@.kind == 'RECENT_DISPENSING')].severity").value(List.of("PRECAUTION")));

        // another customer's file stays private to its pharmacy
        Pharmacy other = onboardPharmacy("Pharmacie Voisine");
        call(get("/api/v1/customers/" + customerId + "/health"), other.ownerToken(), null)
                .andExpect(status().isNotFound());
    }

    private UUID createDrug(String designation, String dci, String atc) throws Exception {
        String first12 = "34009" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
        String cip13 = first12 + Cip13.checkDigit(first12);
        var result = call(put("/api/platform/catalog/products/" + cip13), platformToken, """
                {"designation":"%s","dci":"%s","atcCode":"%s","listing":"NONE","vatRate":0,"publicPriceCents":1000,"active":true}
                """.formatted(designation, dci, atc))
                .andExpect(status().isOk());
        return UUID.fromString(read(result, "$.id"));
    }

    private static String sale(List<UUID> products, long unitPrice, String overridesJson) {
        UUID id = UUID.randomUUID();
        StringBuilder lines = new StringBuilder();
        for (UUID product : products) {
            lines.append(lines.isEmpty() ? "" : ",")
                    .append("{\"productId\":\"").append(product).append("\",\"quantity\":1,\"unitPriceCents\":")
                    .append(unitPrice).append(",\"discountCents\":0}");
        }
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-TEST-%s","occurredAt":"%s",
                 "lines":[%s],
                 "payments":[{"method":"CASH","amountCents":%d}],
                 "safetyOverrides":%s}
                """.formatted(id, id.toString().substring(0, 6), Instant.now(), lines, unitPrice * products.size(),
                overridesJson.trim());
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private <T> T inTenant(Pharmacy pharmacy, java.util.function.Supplier<T> action) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test", new TenantId(pharmacy.tenantId())));
        return TenantContext.call(context, action);
    }
}
