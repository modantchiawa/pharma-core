package com.pharmacore.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import com.pharmacore.support.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class TenantIsolationIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void pharmacyNeverSeesAnotherPharmacysData() throws Exception {
        var alpha = onboardPharmacy("Alpha");
        var beta = onboardPharmacy("Beta");
        var product = createProduct("DOLIPRANE 1000MG CPR", "NONE", 216);
        receiveLot(alpha, product, "A-001", "2028-01-31", 40);

        call(get("/api/v1/inventory/stocks"), alpha.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].available").value(40));
        call(get("/api/v1/inventory/stocks"), beta.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void rowLevelSecurityHoldsEvenForRawSqlWithoutWhereClause() throws Exception {
        var gamma = onboardPharmacy("Gamma");
        var delta = onboardPharmacy("Delta");
        var product = createProduct("SPASFON LYOC 80MG", "NONE", 350);
        receiveLot(gamma, product, "G-1", "2028-06-30", 12);

        long seenByDelta = countLots(delta.tenantId());
        long seenByGamma = countLots(gamma.tenantId());
        long seenWithoutContext = tx.execute(status ->
                jdbc.sql("SELECT count(*) FROM stock_lot").query(Long.class).single());

        assertThat(seenByGamma).isEqualTo(1);
        assertThat(seenByDelta).isZero();
        assertThat(seenWithoutContext).isZero();
    }

    @Test
    void brandingIsPerPharmacyAndPublicBySlug() throws Exception {
        var epsilon = onboardPharmacy("Epsilon");
        call(put("/api/v1/pharmacy/branding"), epsilon.ownerToken(), """
                {"displayName":"Pharmacie Epsilon","countryCode":"FR","primaryColor":"#1D4ED8",
                 "secondaryColor":"#1E3A8A","accentColor":"#F97316","receiptHeader":"Merci de votre visite",
                 "legalIdentifier":"12345678900011","ordonnancierPrefix":"EPS"}
                """).andExpect(status().isOk());

        mvc.perform(get("/api/public/pharmacies/" + epsilon.slug() + "/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branding.displayName").value("Pharmacie Epsilon"))
                .andExpect(jsonPath("$.branding.primaryColor").value("#1D4ED8"));
        mvc.perform(get("/api/public/pharmacies/unknown-" + shortId() + "/branding"))
                .andExpect(status().isNotFound());
    }

    @Test
    void permissionMatrixIsEnforcedAndForgedTokensAreRejected() throws Exception {
        var zeta = onboardPharmacy("Zeta");
        call(get("/api/v1/me"), zeta.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("TITULAIRE"));

        String cashierRole = JsonPath.<java.util.List<String>>read(call(get("/api/v1/roles"), zeta.ownerToken(), null)
                .andReturn().getResponse().getContentAsString(), "$[?(@.code == 'CAISSIER')].id").getFirst();
        call(post("/api/v1/users"), zeta.ownerToken(), """
                {"externalSubject":"cashier-%s","email":"cashier-%s@example.org","displayName":"Camille",
                 "roleIds":["%s"]}
                """.formatted(zeta.slug(), zeta.slug(), cashierRole)).andExpect(status().isCreated());
        String cashierToken = JWT.pharmacist(zeta.tenantId(), "cashier-" + zeta.slug());

        call(get("/api/v1/roles"), cashierToken, null).andExpect(status().isForbidden());
        call(get("/api/v1/inventory/stocks"), cashierToken, null).andExpect(status().isOk());
        call(get("/api/platform/tenants"), zeta.ownerToken(), null).andExpect(status().isForbidden());

        // Same issuer and claims, signed with another key
        String forged = new TestJwtIssuer().pharmacist(zeta.tenantId(), "owner-" + zeta.slug());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
        // Valid signature but the subject is not registered in that pharmacy
        call(get("/api/v1/me"), JWT.pharmacist(zeta.tenantId(), "stranger"), null)
                .andExpect(status().isUnauthorized());
    }

    private long countLots(java.util.UUID tenant) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test", new TenantId(tenant)));
        return TenantContext.call(context, () -> tx.execute(status ->
                jdbc.sql("SELECT count(*) FROM stock_lot").query(Long.class).single()));
    }
}
