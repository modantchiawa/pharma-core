package com.pharmacore.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pharmacore.subscription.application.LicenseIssuer;
import com.pharmacore.subscription.application.LicenseKeyRing;
import com.pharmacore.subscription.domain.LicenseClaims;
import com.pharmacore.subscription.domain.LicenseCodec;
import com.pharmacore.subscription.domain.LicenseKeys;
import com.pharmacore.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Subscriptions on the cloud: payments recorded by the vendor extend the paid period and
 * re-issue the license; a suspension blocks sales and modifications at once while reading,
 * exports and login keep working; the vendor is never blocked; payments stay per pharmacy.
 */
class SubscriptionIT extends IntegrationTest {

    @Autowired
    LicenseIssuer issuer;

    @Autowired
    LicenseKeyRing keys;

    @Test
    void paymentExtendsThePaidPeriodFromItsEndAndReissuesTheLicense() throws Exception {
        var pharmacy = onboardPharmacy("Abonnement");
        String base = "/api/platform/subscriptions/" + pharmacy.tenantId();
        long serialBefore = Long.parseLong(read(call(get(base), platformToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan").value("PRO"))
                .andExpect(jsonPath("$.subscription.state.status").value("ACTIVE")), "$.subscription.license.serial"));

        var first = call(post(base + "/payments"), platformToken, """
                {"amountXof":135000,"method":"MOBILE_MONEY","reference":"Airtel 58213497","months":3}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].amountXof").value(135000))
                .andExpect(jsonPath("$.payments[0].recordedBy").value("Ops ops-1"))
                .andExpect(jsonPath("$.subscription.license.serial").value(serialBefore + 1));
        Instant paidUntil = Instant.parse(read(first, "$.subscription.paidUntil"));
        Instant expected = Instant.now().atZone(ZoneId.of("Africa/Niamey")).plusMonths(3).toInstant();
        assertThat(Duration.between(paidUntil, expected).abs()).isLessThan(Duration.ofMinutes(1));

        // a second payment extends from the end of the paid period, not from today
        var second = call(post(base + "/payments"), platformToken, """
                {"amountXof":45000,"method":"ESPECES","months":1,"paidAt":"%s","note":"Reçu 0042"}"""
                .formatted(LocalDate.now().minusDays(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.payments[?(@.note == 'Reçu 0042')].periodFrom").value(paidUntil.toString()));
        Instant extended = Instant.parse(read(second, "$.subscription.paidUntil"));
        assertThat(extended).isEqualTo(paidUntil.atZone(ZoneId.of("Africa/Niamey")).plusMonths(1).toInstant());

        // the license in force says so, signed by the cloud's license key
        var license = issuer.current(pharmacy.tenantId());
        LicenseClaims claims = LicenseCodec.verify(license.token(), kid -> keys.publicKeys().stream()
                .filter(k -> k.kid().equals(kid)).findFirst().map(k -> LicenseKeys.parsePublic(k.publicKeyPem())));
        assertThat(claims.serial()).isEqualTo(serialBefore + 2);
        assertThat(claims.paidUntil()).isEqualTo(extended);
        assertThat(claims.graceUntil()).isEqualTo(extended.plus(Duration.ofDays(3)));
        assertThat(claims.validUntil()).isEqualTo(claims.issuedAt().plus(Duration.ofDays(15)));
        assertThat(claims.status()).isEqualTo(LicenseClaims.Status.ACTIVE);

        // plan change re-issues it too; an unknown plan is refused
        call(put(base + "/plan"), platformToken, "{\"plan\":\"RESEAU\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan").value("RESEAU"))
                .andExpect(jsonPath("$.subscription.license.serial").value(serialBefore + 3));
        call(put(base + "/plan"), platformToken, "{\"plan\":\"OR\"}")
                .andExpect(status().isUnprocessableContent());
        call(post(base + "/payments"), platformToken, """
                {"amountXof":45000,"method":"ESPECES","months":1,"paidAt":"%s"}""".formatted(LocalDate.now().plusDays(3)))
                .andExpect(status().isUnprocessableContent());

        // the owner sees the same on « Mon abonnement »
        call(get("/api/v1/subscription"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.code").value("RESEAU"))
                .andExpect(jsonPath("$.plan.monthlyPriceXof").value(75000))
                .andExpect(jsonPath("$.payments.length()").value(2))
                .andExpect(jsonPath("$.status.paidUntil").value(extended.toString()))
                .andExpect(jsonPath("$.status.state.status").value("ACTIVE"))
                .andExpect(jsonPath("$.status.mode").value("CLOUD"))
                .andExpect(jsonPath("$.vendor.email").value("abonnements@pharmacore.io"));
        call(get("/api/platform/subscriptions"), platformToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tenantId == '" + pharmacy.tenantId() + "')].lastPaymentXof").value(135000));
    }

    @Test
    void suspensionBlocksSalesAndModificationsButNotReadingExportsOrLogin() throws Exception {
        var pharmacy = onboardPharmacy("Suspendue");
        UUID product = createProduct("PARACETAMOL 500MG CPR", "NONE", 650);
        receiveLot(pharmacy, product, "PCM-1", "2028-06-30", 20);
        Instant beforeSuspension = Instant.now().minus(10, ChronoUnit.MINUTES);

        call(post("/api/platform/subscriptions/" + pharmacy.tenantId() + "/suspension"), platformToken,
                "{\"reason\":\"Deux mois impayés\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.tenantStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.subscription.suspensionReason").value("Deux mois impayés"))
                .andExpect(jsonPath("$.subscription.state.status").value("BLOCKED"))
                .andExpect(jsonPath("$.subscription.state.reason").value("SUSPENDED"));

        // blocked at once: no sale, no modification, a French explanation
        call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(UUID.randomUUID(), product, Instant.now()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("license-blocked"))
                .andExpect(jsonPath("$.reason").value("SUSPENDED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Abonnement suspendu")));
        call(post("/api/v1/customers"), pharmacy.ownerToken(), "{\"displayName\":\"Mme Zara\",\"creditLimitCents\":0}")
                .andExpect(status().isLocked());
        call(post("/api/v1/inventory/lots"), pharmacy.ownerToken(), """
                {"productId":"%s","lotNumber":"X","expiryDate":"2028-01-31","quantity":1,"unitCostCents":1}"""
                .formatted(product))
                .andExpect(status().isLocked());

        // reading, the prescription register export, the state and login keep working
        call(get("/api/v1/inventory/stocks"), pharmacy.ownerToken(), null).andExpect(status().isOk());
        call(get("/api/v1/sales"), pharmacy.ownerToken(), null).andExpect(status().isOk());
        call(get("/api/v1/prescriptions/register/export?from=" + LocalDate.now().minusDays(30) + "&to=" + LocalDate.now()),
                pharmacy.ownerToken(), null).andExpect(status().isOk());
        call(get("/api/v1/subscription/state"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.status").value("BLOCKED"))
                .andExpect(jsonPath("$.state.reason").value("SUSPENDED"))
                .andExpect(jsonPath("$.state.blockedSince").exists());
        String token = login(pharmacy.slug(), pharmacy.ownerEmail(), pharmacy.ownerPassword());
        call(put("/api/v1/me/password"), token, """
                {"currentPassword":"%s","newPassword":"Niamey2026ok"}""".formatted(pharmacy.ownerPassword()))
                .andExpect(status().isNoContent());
        token = login(pharmacy.slug(), pharmacy.ownerEmail(), "Niamey2026ok");

        // offline terminals: a sale made before the suspension is recorded, a later one is held
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        call(post("/api/v1/pos/sync/sales"), token, "{\"sales\":[" + sale(before, product, beforeSuspension) + ","
                + sale(after, product, Instant.now()) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].clientSaleId").value(before.toString()))
                .andExpect(jsonPath("$.outcomes[0].type").value("ACCEPTED"))
                .andExpect(jsonPath("$.outcomes[1].clientSaleId").value(after.toString()))
                .andExpect(jsonPath("$.outcomes[1].type").value("RETRY"));

        // the vendor is never blocked
        createProduct("IBUPROFENE 400MG CPR", "NONE", 1100);
        call(get("/api/platform/subscriptions/" + pharmacy.tenantId()), platformToken, null).andExpect(status().isOk());

        // reactivation unblocks at once, and the held sale goes through
        call(post("/api/platform/subscriptions/" + pharmacy.tenantId() + "/reactivation"), platformToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.tenantStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.suspendedAt").doesNotExist())
                .andExpect(jsonPath("$.subscription.state.status").value("ACTIVE"));
        call(post("/api/v1/pos/sync/sales"), token, "{\"sales\":[" + sale(after, product, Instant.now()) + "]}")
                .andExpect(jsonPath("$.outcomes[0].type").value("ACCEPTED"));
        call(post("/api/v1/sales"), token, sale(UUID.randomUUID(), product, Instant.now()))
                .andExpect(status().isCreated());
    }

    @Test
    void paymentsStayWithTheirPharmacy() throws Exception {
        var first = onboardPharmacy("Premiere");
        var second = onboardPharmacy("Seconde");
        call(post("/api/platform/subscriptions/" + first.tenantId() + "/payments"), platformToken, """
                {"amountXof":90000,"method":"VIREMENT","reference":"VIR-1","months":2}""")
                .andExpect(status().isCreated());
        call(post("/api/platform/subscriptions/" + second.tenantId() + "/payments"), platformToken, """
                {"amountXof":45000,"method":"CHEQUE","reference":"CHQ-2","months":1}""")
                .andExpect(status().isCreated());

        call(get("/api/v1/subscription"), first.ownerToken(), null)
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].reference").value("VIR-1"));
        call(get("/api/v1/subscription"), second.ownerToken(), null)
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].reference").value("CHQ-2"));
        // a pharmacy cannot reach the vendor console
        call(get("/api/platform/subscriptions/" + second.tenantId()), first.ownerToken(), null)
                .andExpect(status().isForbidden());
        call(post("/api/platform/subscriptions/" + first.tenantId() + "/payments"), first.ownerToken(), """
                {"amountXof":1,"method":"AUTRE","months":36}""")
                .andExpect(status().isForbidden());
        List<String> references = com.jayway.jsonpath.JsonPath.read(call(get("/api/platform/subscriptions/"
                + first.tenantId()), platformToken, null).andReturn().getResponse().getContentAsString(),
                "$.payments[*].reference");
        assertThat(references).containsExactly("VIR-1");
    }

    private static String sale(UUID clientSaleId, UUID product, Instant occurredAt) {
        return """
                {"clientSaleId":"%s","terminalId":"T01","ticketNumber":"T01-%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":1,"unitPriceCents":650,"discountCents":0}],
                 "payments":[{"method":"CASH","amountCents":650}]}
                """.formatted(clientSaleId, clientSaleId.toString().substring(0, 6), occurredAt, product);
    }
}
