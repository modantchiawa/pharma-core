package com.pharmacore.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.followup.application.FollowUpGenerator;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FollowUpIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    FollowUpGenerator generator;

    @Test
    void productWaitIsAnsweredByTheDeliveryAndSentByWhatsApp() throws Exception {
        Pharmacy pharmacy = onboardPharmacy("Pharmacie du Suivi");
        UUID product = createProduct("AMOXICILLINE 500", "NONE", 1_500);
        String consenting = customer(pharmacy, "Hadiza Moussa", "90 12 34 56", true);
        String silent = customer(pharmacy, "Issa Garba", "96 00 00 00", false);

        // without consent, no wait can be recorded
        call(post("/api/v1/followup/wishes"), pharmacy.ownerToken(), """
                {"customerId":"%s","productId":"%s","quantity":1}""".formatted(silent, product))
                .andExpect(status().is(422));
        String wishId = read(call(post("/api/v1/followup/wishes"), pharmacy.ownerToken(), """
                {"customerId":"%s","productId":"%s","quantity":2}""".formatted(consenting, product))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING")), "$.id");
        // the same wait is not duplicated
        call(post("/api/v1/followup/wishes"), pharmacy.ownerToken(), """
                {"customerId":"%s","productId":"%s","quantity":1}""".formatted(consenting, product))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(wishId));

        // the delivery closes the wait and queues the message, pre-filled for WhatsApp
        receiveLot(pharmacy, product, "L-1", LocalDate.now().plusYears(1).toString(), 10);
        call(get("/api/v1/followup/wishes?status=NOTIFIED"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(wishId));
        String queue = body(call(get("/api/v1/followup/messages?status=PENDING"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.messages[0].kind").value("PRODUCT_AVAILABLE"))
                .andExpect(jsonPath("$.messages[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$.messages[0].phone").value("22790123456")));
        String link = JsonPath.read(queue, "$.messages[0].whatsappLink");
        String text = JsonPath.read(queue, "$.messages[0].body");
        String messageId = JsonPath.read(queue, "$.messages[0].id");
        assertThat(link).startsWith("https://wa.me/22790123456?text=");
        assertThat(text).contains("Hadiza Moussa").contains("AMOXICILLINE 500").contains("Pharmacie du Suivi");

        // a second delivery does not write to the customer again
        receiveLot(pharmacy, product, "L-2", LocalDate.now().plusYears(1).toString(), 10);
        call(get("/api/v1/followup/messages?status=PENDING"), pharmacy.ownerToken(), null)
                .andExpect(jsonPath("$.pending").value(1));

        // the team sent it from their phone
        call(post("/api/v1/followup/messages/" + messageId + "/sent"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentBy").value("Dr Pharmacie du Suivi"));
        call(post("/api/v1/followup/messages/" + messageId + "/sent"), pharmacy.ownerToken(), null)
                .andExpect(status().is(422));
    }

    @Test
    void dailyRulesQueueTreatmentEndsAndCreditRemindersOnce() throws Exception {
        Pharmacy pharmacy = onboardPharmacy("Pharmacie des Rappels");
        UUID product = createProduct("METFORMINE 850", "NONE", 2_000);
        receiveLot(pharmacy, product, "L-1", LocalDate.now().plusYears(1).toString(), 100);
        String customerId = customer(pharmacy, "Aïchatou Garba", "+227 91 11 11 11", true);

        // a 10-day treatment sold 7 days ago ends the day after tomorrow: notice time
        Instant soldAt = Instant.now().minus(7, ChronoUnit.DAYS);
        call(post("/api/v1/sales"), pharmacy.ownerToken(), """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-1","occurredAt":"%s","customerId":"%s",
                 "lines":[{"productId":"%s","quantity":1,"unitPriceCents":2000,"discountCents":0,
                           "posology":{"morning":1,"noon":0,"evening":1,"bedtime":0,"durationDays":10,"withFood":true,
                                       "instructions":null}}],
                 "payments":[{"method":"CASH","amountCents":2000}]}""".formatted(UUID.randomUUID(), soldAt, customerId, product))
                .andExpect(status().isCreated());
        // a credit sale 40 days ago, never settled
        call(post("/api/v1/sales"), pharmacy.ownerToken(), """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-2","occurredAt":"%s","customerId":"%s",
                 "lines":[{"productId":"%s","quantity":1,"unitPriceCents":2000,"discountCents":0}],
                 "payments":[{"method":"CREDIT","amountCents":2000}]}"""
                .formatted(UUID.randomUUID(), Instant.now().minus(40, ChronoUnit.DAYS), customerId, product))
                .andExpect(status().isCreated());
        // credit reminders are off by default: the owner turns them on
        call(put("/api/v1/followup/settings"), pharmacy.ownerToken(), """
                {"enabled":true,"treatmentEndEnabled":true,"renewalEnabled":true,"renewalDaysAhead":3,
                 "productWishEnabled":true,"creditReminderEnabled":true,"creditReminderAfterDays":30,
                 "creditReminderEveryDays":14,"templates":{"CREDIT_REMINDER":"{client}, solde {montant} chez {pharmacie}."},
                 "smsGatewayUrl":null,"smsGatewayToken":null,"smsSender":null,
                 "digestEnabled":true,"digestHour":21,"digestPhone":"+227 90 00 00 00"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.creditReminderEnabled").value(true))
                .andExpect(jsonPath("$.values.templates.CREDIT_REMINDER").value("{client}, solde {montant} chez {pharmacie}."));

        FollowUpGenerator.Report first = inTenant(pharmacy, generator::generate);
        FollowUpGenerator.Report second = inTenant(pharmacy, generator::generate);
        assertThat(first.treatmentEnds()).isEqualTo(1);
        assertThat(first.creditReminders()).isEqualTo(1);
        assertThat(second.total()).isZero();

        String queue = body(call(get("/api/v1/followup/messages"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(2)));
        List<String> kinds = JsonPath.read(queue, "$.messages[*].kind");
        List<String> bodies = JsonPath.read(queue, "$.messages[*].body");
        assertThat(kinds).containsExactlyInAnyOrder("TREATMENT_END", "CREDIT_REMINDER");
        assertThat(bodies).anySatisfy(text -> assertThat(text).contains("METFORMINE 850").contains("se termine le"));
        assertThat(bodies).anySatisfy(text -> assertThat(text).isEqualTo("Aïchatou Garba, solde 2 000 F chez Pharmacie des Rappels."));

        // a cancelled message stays out of the queue; the team can write freely to a consenting customer
        String creditMessage = JsonPath.<List<String>>read(queue, "$.messages[?(@.kind == 'CREDIT_REMINDER')].id").getFirst();
        call(post("/api/v1/followup/messages/" + creditMessage + "/cancel"), pharmacy.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        call(post("/api/v1/followup/messages"), pharmacy.ownerToken(), """
                {"customerId":"%s","text":"Vos lunettes sont arrivées."}""".formatted(customerId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.whatsappLink").value(org.hamcrest.Matchers.containsString("lunettes")));

        // nothing of this leaves the pharmacy
        Pharmacy other = onboardPharmacy("Pharmacie Voisine");
        call(get("/api/v1/followup/messages"), other.ownerToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(0));
        long rows = inTenant(other, () -> new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT count(*) FROM customer_message").query(Long.class).single()));
        assertThat(rows).isZero();
    }

    private String customer(Pharmacy pharmacy, String name, String phone, boolean consent) throws Exception {
        return read(call(post("/api/v1/customers"), pharmacy.ownerToken(), """
                {"displayName":"%s","phone":"%s","creditLimitCents":100000,"messagingConsent":%s,"preferredChannel":"WHATSAPP"}"""
                .formatted(name, phone, consent))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messagingConsent").value(consent)), "$.id");
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private <T> T inTenant(Pharmacy pharmacy, java.util.function.Supplier<T> action) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test", new TenantId(pharmacy.tenantId())));
        return TenantContext.call(context, action);
    }
}
