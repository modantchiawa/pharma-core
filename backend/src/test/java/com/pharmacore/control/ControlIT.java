package com.pharmacore.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.control.application.WebhookDispatcher;
import com.pharmacore.control.domain.WebhookSignature;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal control end to end: four eyes on stock exits, cancellations and returns; blind
 * receiving and separation of duties; alerts raised, pushed and delivered to a signed webhook
 * through the outbox; the narcotics register; the daily micro-count; isolation of every table.
 */
class ControlIT extends IntegrationTest {

    private static final ZoneId NIAMEY = ZoneId.of("Africa/Niamey");
    private static final String PIN = "2468";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    WebhookDispatcher dispatcher;

    @Autowired
    @Qualifier("brokerChannel")
    AbstractSubscribableChannel brokerChannel;

    private record Staff(UUID id, String email, String token) {
    }

    // ------------------------------------------------------------------ four eyes

    @Test
    void stockExitsAboveTheThresholdOrOnNarcoticsNeedASecondPerson() throws Exception {
        var pharmacy = onboardPharmacy("Quatre Yeux");
        Staff owner = owner(pharmacy);
        Staff adjoint = staff(pharmacy, "ADJOINT", "adjoint", PIN);
        Staff manager = staff(pharmacy, "GESTIONNAIRE_STOCK", "gestionnaire", "1357");
        UUID insulin = createProduct("INSULINE 100UI FL", "NONE", 9_000);
        UUID morphine = createProduct("MORPHINE 10MG CPR", "STUPEFIANT", 2_000);
        String insulinLot = lot(owner, insulin, "INS-1", 20, 5_000);
        String morphineLot = lot(owner, morphine, "MOR-1", 10, 100);

        // below the threshold (10 000): the manager writes off alone
        call(post("/api/v1/inventory/lots/" + insulinLot + "/write-offs"), manager.token(), """
                {"kind":"CASSE","quantity":2,"reason":"Flacons tombés"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valueCents").value(10_000))
                .andExpect(jsonPath("$.approvedBy").doesNotExist());

        // above: refused without a second person, with a French message
        call(post("/api/v1/inventory/lots/" + insulinLot + "/write-offs"), manager.token(), """
                {"kind":"PERTE","quantity":3,"reason":"Introuvables"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-required"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Seconde validation obligatoire au-delà de 10 000 F CFA")));
        // the requester cannot be their own second person
        call(post("/api/v1/inventory/lots/" + insulinLot + "/write-offs"), manager.token(), """
                {"kind":"PERTE","quantity":3,"reason":"Introuvables","approverEmail":"%s","approverPin":"1357"}"""
                .formatted(manager.email()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-same-person"));
        // a wrong PIN is refused
        call(post("/api/v1/inventory/lots/" + insulinLot + "/write-offs"), manager.token(), """
                {"kind":"PERTE","quantity":3,"reason":"Introuvables","approverEmail":"%s","approverPin":"0000"}"""
                .formatted(adjoint.email()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("bad-credentials"));
        // a colleague without CONTROL_APPROVE cannot sign
        Staff cashier = staff(pharmacy, "CAISSIER", "caissier", "8642");
        call(post("/api/v1/inventory/lots/" + insulinLot + "/write-offs"), manager.token(), """
                {"kind":"PERTE","quantity":3,"reason":"Introuvables","approverEmail":"%s","approverPin":"8642"}"""
                .formatted(cashier.email()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-not-authorized"));
        // the pharmacist adjoint signs on the manager's terminal
        call(post("/api/v1/inventory/lots/" + insulinLot + "/write-offs"), manager.token(), """
                {"kind":"PERTE","quantity":3,"reason":"Introuvables","approverEmail":"%s","approverPin":"%s"}"""
                .formatted(adjoint.email(), PIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valueCents").value(15_000))
                .andExpect(jsonPath("$.approvedBy").value(adjoint.id().toString()));
        assertThat(available(pharmacy, insulin)).isEqualTo(15);

        // a manual adjustment follows the same rule (3 × 5 000 = 15 000 at cost)
        call(put("/api/v1/inventory/lots/" + insulinLot + "/quantity"), manager.token(), """
                {"countedQuantity":12,"reason":"Recomptage"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-required"));
        call(put("/api/v1/inventory/lots/" + insulinLot + "/quantity"), manager.token(), """
                {"countedQuantity":14,"reason":"Recomptage"}""")
                .andExpect(status().isNoContent());

        // narcotics: always, whatever the amount (1 × 100)
        call(post("/api/v1/inventory/lots/" + morphineLot + "/write-offs"), owner.token(), """
                {"kind":"CASSE","quantity":1,"reason":"Blister écrasé"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-required"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Seconde validation obligatoire pour un stupéfiant")));
        call(put("/api/v1/inventory/lots/" + morphineLot + "/quantity"), owner.token(), """
                {"countedQuantity":9,"reason":"Recomptage"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-required"));
        call(post("/api/v1/inventory/lots/" + morphineLot + "/write-offs"), owner.token(), """
                {"kind":"CASSE","quantity":1,"reason":"Blister écrasé","approverEmail":"%s","approverPin":"%s"}"""
                .formatted(adjoint.email(), PIN))
                .andExpect(status().isCreated());

        // both signatures, the reason and the amount are kept
        LocalDate today = LocalDate.now(NIAMEY);
        String approvals = body(call(get("/api/v1/control/approvals?from=" + today + "&to=" + today), owner.token(),
                null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(approvals, "$[*].action"))
                .containsExactlyInAnyOrder("STOCK_WRITE_OFF", "STOCK_WRITE_OFF");
        assertThat(JsonPath.<List<String>>read(approvals, "$[*].approvedBy")).containsOnly("adjoint");
        assertThat(JsonPath.<List<String>>read(approvals, "$[*].requestedBy")).contains("gestionnaire");
        assertThat(JsonPath.<List<Boolean>>read(approvals, "$[?(@.amountCents == 100)].narcotic")).containsOnly(true);
        assertThat(JsonPath.<List<String>>read(approvals, "$[?(@.amountCents == 15000)].reason"))
                .containsExactly("Introuvables");
    }

    @Test
    void paidCancellationsAndReturnsAboveTheThresholdNeedASecondPerson() throws Exception {
        var pharmacy = onboardPharmacy("Retours");
        Staff owner = owner(pharmacy);
        Staff adjoint = staff(pharmacy, "ADJOINT", "adjoint", PIN);
        UUID product = createProduct("AUGMENTIN 1G CPR", "NONE", 2_000);
        lot(owner, product, "A-1", 50, 1_200);
        call(post("/api/v1/cash/sessions"), owner.token(), """
                {"terminalId":"T-TEST","openingFloatCents":20000}""").andExpect(status().isCreated());

        // 3 × 2 000 = 6 000 paid: above the 5 000 threshold, even the owner needs a colleague
        String big = sale(owner.token(), product, 3, 2_000, "[{\"method\":\"CASH\",\"amountCents\":6000}]", null);
        call(post("/api/v1/sales/" + big + "/cancellation"), owner.token(), """
                {"reason":"Client mécontent","terminalId":"T-TEST"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-required"));
        call(post("/api/v1/sales/" + big + "/cancellation"), owner.token(), """
                {"reason":"Client mécontent","terminalId":"T-TEST","supervisorEmail":"%s","supervisorPin":"%s"}"""
                .formatted(owner.email(), PIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-same-person"));
        call(post("/api/v1/sales/" + big + "/cancellation"), owner.token(), """
                {"reason":"Client mécontent","terminalId":"T-TEST","supervisorEmail":"%s","supervisorPin":"%s"}"""
                .formatted(adjoint.email(), PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // below: alone
        String small = sale(owner.token(), product, 1, 2_000, "[{\"method\":\"CASH\",\"amountCents\":2000}]", null);
        call(post("/api/v1/sales/" + small + "/cancellation"), owner.token(), """
                {"reason":"Erreur","terminalId":"T-TEST"}""")
                .andExpect(status().isOk());

        // partial return: 3 of 4 units back (6 000 refunded) needs the second person, 1 unit does not
        String ticket = sale(owner.token(), product, 4, 2_000, "[{\"method\":\"CASH\",\"amountCents\":8000}]", null);
        int before = available(pharmacy, product);
        call(post("/api/v1/sales/" + ticket + "/returns"), owner.token(), """
                {"lines":[{"lineNo":1,"quantity":3}],"reason":"Traitement changé","terminalId":"T-TEST"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-required"));
        call(post("/api/v1/sales/" + ticket + "/returns"), owner.token(), """
                {"lines":[{"lineNo":1,"quantity":3}],"reason":"Traitement changé","terminalId":"T-TEST",
                 "approverEmail":"%s","approverPin":"%s"}""".formatted(adjoint.email(), PIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundCents").value(6_000))
                .andExpect(jsonPath("$.approvedBy").value(adjoint.id().toString()));
        call(post("/api/v1/sales/" + ticket + "/returns"), owner.token(), """
                {"lines":[{"lineNo":1,"quantity":2}],"reason":"Encore","terminalId":"T-TEST"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("return-quantity"));
        call(post("/api/v1/sales/" + ticket + "/returns"), owner.token(), """
                {"lines":[{"lineNo":1,"quantity":1}],"reason":"Dernière boîte","terminalId":"T-TEST"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundCents").value(2_000));
        assertThat(available(pharmacy, product)).isEqualTo(before + 4);
        call(get("/api/v1/sales/" + ticket), owner.token(), null)
                .andExpect(jsonPath("$.lines[0].returnedQuantity").value(4));

        // the refunds left the drawer: 20 000 + 6 000 + 2 000 + 8 000 cash in, 6 000 + 2 000 + 8 000 out
        String session = read(call(get("/api/v1/cash/sessions/open?terminalId=T-TEST"), owner.token(), null),
                "$.sessionId");
        call(get("/api/v1/cash/sessions/" + session), owner.token(), null)
                .andExpect(jsonPath("$.expectedCashCents").value(20_000));
        // the integrity check agrees with the lots
        call(post("/api/v1/inventory/integrity-checks"), owner.token(), null)
                .andExpect(jsonPath("$.mismatches").value(0));
    }

    // ------------------------------------------------------------------ receiving

    @Test
    void blindReceivingHidesQuantitiesAndSeparationOfDutiesWarnsOrBlocks() throws Exception {
        var pharmacy = onboardPharmacy("Aveugle");
        Staff owner = owner(pharmacy);
        String receiverRole = read(call(post("/api/v1/roles"), owner.token(), """
                {"code":"RECEPTION","name":"Réception","maxDiscountPercent":0,
                 "permissions":["STOCK_VIEW","STOCK_RECEIVE","PROCUREMENT_VIEW"]}""")
                .andExpect(status().isCreated()), "$.id");
        Staff receiver = staffWithRoleId(pharmacy, receiverRole, "receveur", "9753");
        UUID product = createProduct("COARTEM 80/480 CPR", "NONE", 2_600);
        String supplier = read(call(post("/api/v1/procurement/suppliers"), owner.token(), """
                {"name":"Laborex","leadTimeDays":1,"minOrderCents":0}""").andExpect(status().isCreated()), "$.id");
        settings(owner, Map.of("blindReceiving", "true", "receivingSeparation", "\"WARN\""));

        String order = order(owner, supplier, product, 10);
        // the receiving form gets the products without what was ordered
        String receiving = body(call(get("/api/v1/procurement/orders/" + order + "/receiving"), receiver.token(), null)
                .andExpect(status().isOk()));
        assertThat((Boolean) JsonPath.read(receiving, "$.blind")).isTrue();
        assertThat(JsonPath.<List<String>>read(receiving, "$.lines[*].designation")).containsExactly("COARTEM 80/480 CPR");
        assertThat(JsonPath.<List<Object>>read(receiving, "$.lines[*].quantityOrdered")).containsOnlyNulls();
        assertThat(JsonPath.<List<Object>>read(receiving, "$.lines[*].quantityRemaining")).containsOnlyNulls();
        // nor do the order screens for someone who is not a buyer; the buyer still sees them
        call(get("/api/v1/procurement/orders/" + order), receiver.token(), null)
                .andExpect(jsonPath("$.quantitiesHidden").value(true))
                .andExpect(jsonPath("$.lines[0].quantityOrdered").value(0))
                .andExpect(jsonPath("$.orderedUnits").value(0));
        call(get("/api/v1/procurement/orders"), receiver.token(), null)
                .andExpect(jsonPath("$[0].lines[0].quantityOrdered").value(0));
        call(get("/api/v1/procurement/orders/" + order + "/text"), receiver.token(), null)
                .andExpect(status().isUnprocessableContent());
        call(get("/api/v1/procurement/orders/" + order), owner.token(), null)
                .andExpect(jsonPath("$.quantitiesHidden").value(false))
                .andExpect(jsonPath("$.lines[0].quantityOrdered").value(10));

        // 8 came: the difference with the order is computed after the receipt and kept
        call(post("/api/v1/procurement/receipts"), receiver.token(), receipt(supplier, order, product, 8))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.check.blind").value(true))
                .andExpect(jsonPath("$.check.separationWarning").value(false))
                .andExpect(jsonPath("$.check.discrepancyLines").value(1))
                .andExpect(jsonPath("$.check.discrepancyUnits").value(-2))
                .andExpect(jsonPath("$.check.lines[0].expected").value(10))
                .andExpect(jsonPath("$.check.lines[0].received").value(8));
        LocalDate today = LocalDate.now(NIAMEY);
        call(get("/api/v1/control/receipt-checks?from=" + today + "&to=" + today), owner.token(), null)
                .andExpect(jsonPath("$[0].discrepancyUnits").value(-2))
                .andExpect(jsonPath("$[0].receivedBy").value("receveur"));

        // warning mode: the buyer receives their own order, the owner is alerted
        String second = order(owner, supplier, product, 5);
        call(post("/api/v1/procurement/receipts"), owner.token(), receipt(supplier, second, product, 5))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.check.separationWarning").value(true))
                .andExpect(jsonPath("$.check.discrepancyLines").value(0));
        call(get("/api/v1/control/alerts"), owner.token(), null)
                .andExpect(jsonPath("$.alerts[?(@.kind == 'RECEIPT_DISCREPANCY')]").isNotEmpty());

        // blocking mode: the buyer is refused, a colleague receives
        settings(owner, Map.of("blindReceiving", "false", "receivingSeparation", "\"BLOCK\""));
        String third = order(owner, supplier, product, 4);
        call(post("/api/v1/procurement/receipts"), owner.token(), receipt(supplier, third, product, 4))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("separation-of-duties"));
        call(get("/api/v1/procurement/orders/" + third + "/receiving"), receiver.token(), null)
                .andExpect(jsonPath("$.blind").value(false))
                .andExpect(jsonPath("$.lines[0].quantityRemaining").value(4));
        call(post("/api/v1/procurement/receipts"), receiver.token(), receipt(supplier, third, product, 4))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.check.blind").value(false));
    }

    // ------------------------------------------------------------------ alerts

    @Test
    void alertsAreRaisedAndPushedToThePharmacyTopic() throws Exception {
        var pharmacy = onboardPharmacy("Alertes");
        Staff owner = owner(pharmacy);
        Staff adjoint = staff(pharmacy, "ADJOINT", "adjoint", PIN);
        UUID product = createProduct("VENTOLINE SPRAY", "NONE", 3_000);
        String lot = lot(owner, product, "V-1", 30, 3_000);
        List<Message<?>> pushed = new CopyOnWriteArrayList<>();
        ChannelInterceptor spy = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                pushed.add(message);
                return message;
            }
        };
        brokerChannel.addInterceptor(spy);
        try {
            // a large write-off (10 × 3 000 = 30 000 ≥ 25 000)
            call(post("/api/v1/inventory/lots/" + lot + "/write-offs"), owner.token(), """
                    {"kind":"VOL","quantity":10,"reason":"Constaté au rangement","approverEmail":"%s",
                     "approverPin":"%s"}""".formatted(adjoint.email(), PIN))
                    .andExpect(status().isCreated());
            // a cash variance (−3 000 ≥ 2 000)
            String session = read(call(post("/api/v1/cash/sessions"), owner.token(), """
                    {"terminalId":"T-TEST","openingFloatCents":10000}""").andExpect(status().isCreated()),
                    "$.sessionId");
            call(post("/api/v1/cash/sessions/" + session + "/closure"), owner.token(), """
                    {"countedCashCents":7000}""").andExpect(status().isOk());
            // a sale outside the activity hours (none at this time of day, no guard)
            settings(owner, Map.of("activityHours", closedNow()));
            sale(owner.token(), product, 1, 3_000, "[{\"method\":\"CASH\",\"amountCents\":3000}]", null);
            // repeated wrong PINs on the adjoint's account
            for (int i = 0; i < 3; i++) {
                call(put("/api/v1/inventory/lots/" + lot + "/quantity"), owner.token(), """
                        {"countedQuantity":2,"reason":"Test","approverEmail":"%s","approverPin":"9999"}"""
                        .formatted(adjoint.email()))
                        .andExpect(jsonPath("$.code").value("bad-credentials"));
            }
        } finally {
            brokerChannel.removeInterceptor(spy);
        }

        String feed = body(call(get("/api/v1/control/alerts"), owner.token(), null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(feed, "$.alerts[*].kind")).contains("LARGE_ADJUSTMENT",
                "CASH_VARIANCE", "OUTSIDE_HOURS", "PIN_FAILURES");
        assertThat(JsonPath.<List<String>>read(feed, "$.alerts[?(@.kind == 'LARGE_ADJUSTMENT')].severity"))
                .containsExactly("CRITICAL");
        assertThat(JsonPath.<List<String>>read(feed, "$.alerts[?(@.kind == 'PIN_FAILURES')].staffName"))
                .containsExactly("adjoint");
        assertThat(JsonPath.<List<Integer>>read(feed, "$.alerts[?(@.kind == 'CASH_VARIANCE')].amountCents"))
                .containsExactly(-3_000);
        int unread = JsonPath.read(feed, "$.unread");
        assertThat(unread).isGreaterThanOrEqualTo(4);

        // pushed over STOMP to this pharmacy's topic, once committed
        String topic = "/topic/tenants." + pharmacy.tenantId() + ".control-alerts";
        List<Message<?>> mine = pushed.stream()
                .filter(m -> topic.equals(SimpMessageHeaderAccessor.getDestination(m.getHeaders())))
                .toList();
        assertThat(mine).hasSizeGreaterThanOrEqualTo(4);
        assertThat(new String((byte[]) mine.getFirst().getPayload(), StandardCharsets.UTF_8)).contains("\"kind\"");

        // acknowledged one by one or all at once
        String first = JsonPath.read(feed, "$.alerts[0].id");
        call(post("/api/v1/control/alerts/" + first + "/acknowledgement"), owner.token(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").exists());
        call(get("/api/v1/control/alerts/unread-count"), owner.token(), null)
                .andExpect(jsonPath("$.unread").value(unread - 1));
        call(post("/api/v1/control/alerts/acknowledgement"), owner.token(), null).andExpect(status().isOk());
        call(get("/api/v1/control/alerts/unread-count"), owner.token(), null)
                .andExpect(jsonPath("$.unread").value(0));
        // the feed is for the owner's team only
        Staff cashier = staff(pharmacy, "CAISSIER", "caissier", "8642");
        call(get("/api/v1/control/alerts"), cashier.token(), null).andExpect(status().isForbidden());
    }

    @Test
    void webhookOutboxSignsRetriesWithBackoffAndDelivers() throws Exception {
        var pharmacy = onboardPharmacy("Webhook");
        Staff owner = owner(pharmacy);
        AtomicInteger answer = new AtomicInteger(500);
        List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(Map.of("body", body,
                    "signature", exchange.getRequestHeaders().getFirst(WebhookSignature.SIGNATURE_HEADER),
                    "timestamp", exchange.getRequestHeaders().getFirst(WebhookSignature.TIMESTAMP_HEADER)));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        server.start();
        String secret = "owner-shared-secret-2026";
        try {
            settings(owner, Map.of("webhookEnabled", "true",
                    "webhookUrl", "\"http://127.0.0.1:" + server.getAddress().getPort() + "/hook\"",
                    "webhookSecret", "\"" + secret + "\""));
            call(get("/api/v1/control/settings"), owner.token(), null)
                    .andExpect(jsonPath("$.webhookSecretSet").value(true))
                    .andExpect(jsonPath("$.webhookSecret").doesNotExist());
            call(post("/api/v1/control/alerts/test"), owner.token(), null).andExpect(status().isOk());

            // first attempt: the receiver fails, the delivery is rescheduled
            assertThat(inTenant(pharmacy, () -> dispatcher.deliverDue())).isZero();
            assertThat(received).hasSize(1);
            var outbox = outbox(pharmacy);
            assertThat(outbox.get("status")).isEqualTo("PENDING");
            assertThat(outbox.get("attempts")).isEqualTo(1);
            assertThat(outbox.get("last_error")).isEqualTo("HTTP 500");
            assertThat(((java.sql.Timestamp) outbox.get("next_attempt_at")).toInstant())
                    .isAfter(Instant.now().plusSeconds(20));
            // not due yet: nothing is sent
            assertThat(inTenant(pharmacy, () -> dispatcher.deliverDue())).isZero();
            assertThat(received).hasSize(1);

            // once due again and the receiver is back, it is delivered
            answer.set(204);
            inTenant(pharmacy, () -> tx.execute(status -> jdbc.sql(
                    "UPDATE control_webhook_outbox SET next_attempt_at = now() - interval '1 second'").update()));
            assertThat(inTenant(pharmacy, () -> dispatcher.deliverDue())).isEqualTo(1);
            outbox = outbox(pharmacy);
            assertThat(outbox.get("status")).isEqualTo("DELIVERED");
            assertThat(outbox.get("attempts")).isEqualTo(2);
            assertThat(received).hasSize(2);
        } finally {
            server.stop(0);
        }
        var delivery = received.getLast();
        assertThat(WebhookSignature.verify(secret, Long.parseLong(delivery.get("timestamp")), delivery.get("body"),
                delivery.get("signature"))).isTrue();
        String body = delivery.get("body");
        assertThat((String) JsonPath.read(body, "$.event")).isEqualTo("control.alert");
        assertThat((String) JsonPath.read(body, "$.kind")).isEqualTo("TEST");
        assertThat((String) JsonPath.read(body, "$.pharmacy.slug")).isEqualTo(pharmacy.slug());
        assertThat(JsonPath.<Map<String, Object>>read(body, "$")).doesNotContainKeys("customer", "patient");
        call(get("/api/v1/control/webhook/deliveries"), owner.token(), null)
                .andExpect(jsonPath("$[0].status").value("DELIVERED"));
    }

    // ------------------------------------------------------------------ narcotics register

    @Test
    void narcoticsRegisterRunsTheTheoreticalBalanceAndShowsTheDoubleSignature() throws Exception {
        var pharmacy = onboardPharmacy("Stupefiants");
        Staff owner = owner(pharmacy);
        Staff adjoint = staff(pharmacy, "ADJOINT", "adjoint", PIN);
        Staff counter = staff(pharmacy, "PREPARATEUR", "compteur", "1122");
        UUID tramadol = createProduct("TRAMADOL 50MG GEL", "STUPEFIANT", 2_400);
        String lot = lot(owner, tramadol, "T-1", 20, 1_700);
        sale(owner.token(), tramadol, 2, 2_400, "[{\"method\":\"CASH\",\"amountCents\":4800}]",
                "{\"prescriberName\":\"Dr Oumarou\"}");
        call(post("/api/v1/inventory/lots/" + lot + "/write-offs"), owner.token(), """
                {"kind":"CASSE","quantity":1,"reason":"Gélule écrasée","approverEmail":"%s","approverPin":"%s"}"""
                .formatted(adjoint.email(), PIN)).andExpect(status().isCreated());

        // the counter finds 16 instead of 17
        String count = read(call(post("/api/v1/inventory/counts"), counter.token(), """
                {"label":"Coffre"}""").andExpect(status().isCreated()), "$.id");
        String countBody = body(call(get("/api/v1/inventory/counts/" + count), counter.token(), null));
        assertThat(JsonPath.<List<Object>>read(countBody, "$.lines[*].expectedQuantity")).isEmpty();
        String line = JsonPath.<List<String>>read(countBody, "$.lines[*].id").getFirst();
        call(post("/api/v1/inventory/counts/" + count + "/lines/" + line), counter.token(), """
                {"quantity":16}""").andExpect(status().isOk());
        // validating a count of narcotics always takes a second signature
        call(post("/api/v1/inventory/counts/" + count + "/validation"), owner.token(), "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("four-eyes-required"));
        call(post("/api/v1/inventory/counts/" + count + "/validation"), owner.token(), """
                {"notes":"Contrôle du coffre","approverEmail":"%s","approverPin":"%s"}""".formatted(adjoint.email(), PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));

        LocalDate today = LocalDate.now(NIAMEY);
        String register = body(call(get("/api/v1/control/narcotics?from=" + today + "&to=" + today), owner.token(),
                null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(register, "$.products[*].designation")).containsExactly("TRAMADOL 50MG GEL");
        assertThat(JsonPath.<List<String>>read(register, "$.products[0].entries[*].type"))
                .containsExactly("RECEIPT", "SALE", "WRITE_OFF", "COUNT");
        assertThat(JsonPath.<List<Integer>>read(register, "$.products[0].entries[*].balance"))
                .containsExactly(20, 18, 17, 16);
        assertThat((Integer) JsonPath.read(register, "$.products[0].openingBalance")).isZero();
        assertThat((Integer) JsonPath.read(register, "$.products[0].theoreticalBalance")).isEqualTo(16);
        assertThat((Integer) JsonPath.read(register, "$.products[0].onHand")).isEqualTo(16);
        assertThat((Integer) JsonPath.read(register, "$.products[0].lastCount.gap")).isEqualTo(-1);
        assertThat((Boolean) JsonPath.read(register, "$.products[0].gapAtLastCount")).isTrue();
        assertThat((String) JsonPath.read(register, "$.products[0].entries[3].countedBy")).isEqualTo("compteur");
        assertThat((String) JsonPath.read(register, "$.products[0].entries[3].validatedBy")).startsWith("Dr ");
        assertThat((String) JsonPath.read(register, "$.products[0].entries[3].approvedBy")).isEqualTo("adjoint");
        assertThat((String) JsonPath.read(register, "$.products[0].entries[1].reference")).startsWith("Ticket ");
        // the variance on a narcotic raised a critical alert
        call(get("/api/v1/control/alerts"), owner.token(), null)
                .andExpect(jsonPath("$.alerts[?(@.kind == 'COUNT_DISCREPANCY')].severity").value("CRITICAL"));
    }

    // ------------------------------------------------------------------ daily micro-count

    @Test
    void dailyCountIsFrozenForTheDayBlindAndAttributesVariancesToTheStaffOnDuty() throws Exception {
        var pharmacy = onboardPharmacy("Controle Du Jour");
        Staff owner = owner(pharmacy);
        Staff counter = staff(pharmacy, "PREPARATEUR", "compteur", "1122");
        Staff cashier = staff(pharmacy, "CAISSIER", "vendeuse", "3344");
        List<UUID> products = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            UUID product = createProduct("PRODUIT CONTROLE " + i, "NONE", 1_000 + i * 100L);
            lot(owner, product, "C-" + i, 10, 300);
            products.add(product);
        }
        UUID sold = products.getFirst();
        sale(cashier.token(), sold, 1, 1_000, "[{\"method\":\"CASH\",\"amountCents\":1000}]", null);

        String first = body(call(post("/api/v1/control/daily-count"), counter.token(), null).andExpect(status().isOk()));
        List<String> drawn = JsonPath.read(first, "$.items[*].productId");
        assertThat(drawn).hasSize(6).doesNotHaveDuplicates();
        assertThat((String) JsonPath.read(first, "$.status")).isEqualTo("PENDING");
        assertThat(JsonPath.<List<Integer>>read(first, "$.items[*].score")).allMatch(score -> score >= 0 && score <= 100);
        // reloading never changes the draw
        String again = body(call(post("/api/v1/control/daily-count"), owner.token(), null));
        assertThat(JsonPath.<List<String>>read(again, "$.items[*].productId")).containsExactlyElementsOf(drawn);

        // the regular blind count engine, limited to these products
        String started = body(call(post("/api/v1/control/daily-count/start"), counter.token(), null)
                .andExpect(status().isOk()));
        String count = JsonPath.read(started, "$.stockCountId");
        assertThat((String) JsonPath.read(started, "$.status")).isEqualTo("OPEN");
        String restart = body(call(post("/api/v1/control/daily-count/start"), counter.token(), null));
        assertThat((String) JsonPath.read(restart, "$.stockCountId")).isEqualTo(count);
        String lines = body(call(get("/api/v1/inventory/counts/" + count), counter.token(), null));
        assertThat(JsonPath.<List<String>>read(lines, "$.lines[*].productId")).containsExactlyInAnyOrderElementsOf(drawn);
        assertThat(JsonPath.<List<Object>>read(lines, "$.lines[*].expectedQuantity")).isEmpty();

        // every product is found as expected except one, two units short
        String shortProduct = drawn.getFirst();
        List<String> lineIds = JsonPath.read(lines, "$.lines[*].id");
        List<String> lineProducts = JsonPath.read(lines, "$.lines[*].productId");
        for (int i = 0; i < lineIds.size(); i++) {
            int onShelf = lineProducts.get(i).equals(sold.toString()) ? 9 : 10;
            int quantity = lineProducts.get(i).equals(shortProduct) ? onShelf - 2 : onShelf;
            call(post("/api/v1/inventory/counts/" + count + "/lines/" + lineIds.get(i)), counter.token(),
                    "{\"quantity\":" + quantity + "}").andExpect(status().isOk());
        }
        call(post("/api/v1/inventory/counts/" + count + "/validation"), owner.token(), "{}")
                .andExpect(status().isOk());

        String done = body(call(post("/api/v1/control/daily-count"), owner.token(), null));
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("VALIDATED");
        assertThat(JsonPath.<List<String>>read(done, "$.variances[*].productId")).containsExactly(shortProduct);
        assertThat(JsonPath.<List<Integer>>read(done, "$.variances[*].variance")).containsExactly(-2);
        assertThat(JsonPath.<List<String>>read(done, "$.variances[0].staffOnDuty[*].displayName")).contains("vendeuse");
        assertThat(JsonPath.<Boolean>read(done, "$.variances[0].sincePreviousCount")).isFalse();
        // the counter does not see the variances
        String forCounter = body(call(post("/api/v1/control/daily-count"), counter.token(), null));
        assertThat(JsonPath.<List<Object>>read(forCounter, "$.variances")).isEmpty();

        LocalDate today = LocalDate.now(NIAMEY);
        call(get("/api/v1/control/daily-counts?from=" + today + "&to=" + today), owner.token(), null)
                .andExpect(jsonPath("$[0].status").value("VALIDATED"))
                .andExpect(jsonPath("$[0].varianceUnits").value(-2))
                .andExpect(jsonPath("$[0].varianceValueCents").value(-600));
        String overview = body(call(get("/api/v1/control/overview?from=" + today + "&to=" + today), owner.token(),
                null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(overview,
                "$.employees[?(@.displayName == 'vendeuse')].countDiscrepancies")).containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(overview, "$.employees[?(@.displayName == 'vendeuse')].sales"))
                .containsExactly(1);
        String events = body(call(get("/api/v1/control/employees/" + cashier.id()
                + "/events?metric=COUNT_DISCREPANCIES&from=" + today + "&to=" + today), owner.token(), null)
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(events, "$[*].type")).containsExactly("COUNT");
        call(get("/api/v1/control/employees/" + cashier.id() + "/audit?from=" + today + "&to=" + today),
                owner.token(), null)
                .andExpect(jsonPath("$[?(@.label == 'SALE_RECORDED')]").isNotEmpty());
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void everyControlTableIsIsolatedPerPharmacy() throws Exception {
        var alpha = onboardPharmacy("Controle Alpha");
        var beta = onboardPharmacy("Controle Beta");
        Staff owner = owner(alpha);
        Staff adjoint = staff(alpha, "ADJOINT", "adjoint", PIN);
        UUID product = createProduct("ISOLATION CPR", "NONE", 2_000);
        String lot = lot(owner, product, "I-1", 40, 5_000);
        settings(owner, Map.of("webhookEnabled", "true", "webhookUrl", "\"http://127.0.0.1:9/hook\"",
                "webhookSecret", "\"alpha-secret-0123456789\""));
        call(post("/api/v1/inventory/lots/" + lot + "/write-offs"), owner.token(), """
                {"kind":"VOL","quantity":5,"reason":"Isolation","approverEmail":"%s","approverPin":"%s"}"""
                .formatted(adjoint.email(), PIN)).andExpect(status().isCreated());
        call(post("/api/v1/cash/sessions"), owner.token(), """
                {"terminalId":"T-TEST","openingFloatCents":5000}""").andExpect(status().isCreated());
        String ticket = sale(owner.token(), product, 2, 2_000, "[{\"method\":\"CASH\",\"amountCents\":4000}]", null);
        call(post("/api/v1/sales/" + ticket + "/returns"), owner.token(), """
                {"lines":[{"lineNo":1,"quantity":1}],"reason":"Isolation","terminalId":"T-TEST"}""")
                .andExpect(status().isCreated());
        call(post("/api/v1/control/daily-count"), owner.token(), null).andExpect(status().isOk());
        String supplier = read(call(post("/api/v1/procurement/suppliers"), owner.token(), """
                {"name":"Copharni","leadTimeDays":1,"minOrderCents":0}""").andExpect(status().isCreated()), "$.id");
        String order = order(owner, supplier, product, 3);
        call(post("/api/v1/procurement/receipts"), owner.token(), receipt(supplier, order, product, 3))
                .andExpect(status().isCreated());
        String alert = read(call(post("/api/v1/control/alerts/test"), owner.token(), null), "$.id");

        List<String> tables = List.of("control_settings", "control_approval", "stock_write_off", "sale_return",
                "sale_return_line", "control_daily_count", "control_daily_count_item", "goods_receipt_check",
                "control_alert", "control_webhook_outbox");
        for (String table : tables) {
            assertThat(countIn(alpha, table)).as(table + " for alpha").isPositive();
            assertThat(countIn(beta, table)).as(table + " for beta").isZero();
            long withoutContext = tx.execute(status -> jdbc.sql("SELECT count(*) FROM " + table)
                    .query(Long.class).single());
            assertThat(withoutContext).as(table + " without context").isZero();
        }

        LocalDate today = LocalDate.now(NIAMEY);
        call(get("/api/v1/control/alerts"), beta.ownerToken(), null)
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.alerts.length()").value(0));
        call(post("/api/v1/control/alerts/" + alert + "/acknowledgement"), beta.ownerToken(), null)
                .andExpect(status().isNotFound());
        call(get("/api/v1/control/approvals?from=" + today + "&to=" + today), beta.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/control/receipt-checks?from=" + today + "&to=" + today), beta.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/control/daily-counts?from=" + today + "&to=" + today), beta.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
        call(get("/api/v1/control/settings"), beta.ownerToken(), null)
                .andExpect(jsonPath("$.webhookEnabled").value(false))
                .andExpect(jsonPath("$.version").value(0));
        call(get("/api/v1/control/webhook/deliveries"), beta.ownerToken(), null)
                .andExpect(jsonPath("$.length()").value(0));
        String overview = body(call(get("/api/v1/control/overview?from=" + today + "&to=" + today),
                beta.ownerToken(), null));
        assertThat(JsonPath.<List<String>>read(overview, "$.employees[*].displayName")).doesNotContain("adjoint");
    }

    // ------------------------------------------------------------------ helpers

    private Staff owner(Pharmacy pharmacy) throws Exception {
        call(put("/api/v1/me/pin"), pharmacy.ownerToken(), """
                {"password":"%s","pin":"%s"}""".formatted(pharmacy.ownerPassword(), PIN))
                .andExpect(status().isNoContent());
        String id = read(call(get("/api/v1/me"), pharmacy.ownerToken(), null), "$.userId");
        return new Staff(UUID.fromString(id), pharmacy.ownerEmail(), pharmacy.ownerToken());
    }

    private Staff staff(Pharmacy pharmacy, String roleCode, String name, String pin) throws Exception {
        String roles = body(call(get("/api/v1/roles"), pharmacy.ownerToken(), null).andExpect(status().isOk()));
        String roleId = JsonPath.<List<String>>read(roles, "$[?(@.code == '" + roleCode + "')].id").getFirst();
        return staffWithRoleId(pharmacy, roleId, name, pin);
    }

    private Staff staffWithRoleId(Pharmacy pharmacy, String roleId, String name, String pin) throws Exception {
        String email = name + "-" + pharmacy.slug() + "@example.org";
        var registered = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"%s","roleIds":["%s"]}""".formatted(email, name, roleId))
                .andExpect(status().isCreated());
        String password = read(registered, "$.temporaryPassword");
        String token = login(pharmacy.slug(), email, password);
        call(put("/api/v1/me/pin"), token, """
                {"password":"%s","pin":"%s"}""".formatted(password, pin)).andExpect(status().isNoContent());
        String id = read(call(get("/api/v1/me"), token, null), "$.userId");
        return new Staff(UUID.fromString(id), email, token);
    }

    private String lot(Staff owner, UUID product, String number, int quantity, long cost) throws Exception {
        return read(call(post("/api/v1/inventory/lots"), owner.token(), """
                {"productId":"%s","lotNumber":"%s","expiryDate":"%s","quantity":%d,"unitCostCents":%d}"""
                .formatted(product, number, LocalDate.now().plusYears(2), quantity, cost))
                .andExpect(status().isCreated()), "$.lotId");
    }

    private String sale(String token, UUID product, int quantity, long price, String payments, String prescription)
            throws Exception {
        UUID id = UUID.randomUUID();
        return read(call(post("/api/v1/sales"), token, """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"T-%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":%d,"unitPriceCents":%d,"discountCents":0}],
                 "payments":%s%s}""".formatted(id, id.toString().substring(0, 8), Instant.now(), product, quantity,
                price, payments, prescription == null ? "" : ",\"prescription\":" + prescription))
                .andExpect(status().isCreated()), "$.saleId");
    }

    private String order(Staff owner, String supplier, UUID product, int quantity) throws Exception {
        String order = read(call(post("/api/v1/procurement/orders"), owner.token(), """
                {"supplierId":"%s","lines":[{"productId":"%s","quantity":%d,"unitCostCents":1500}]}"""
                .formatted(supplier, product, quantity)).andExpect(status().isCreated()), "$.id");
        call(post("/api/v1/procurement/orders/" + order + "/send"), owner.token(), null).andExpect(status().isOk());
        return order;
    }

    private static String receipt(String supplier, String order, UUID product, int quantity) {
        return """
                {"supplierId":"%s","purchaseOrderId":"%s","supplierInvoiceNumber":"F-%s","lines":[
                  {"productId":"%s","lotNumber":"R-%s","expiryDate":"%s","quantity":%d,"unitCostCents":1500}]}"""
                .formatted(supplier, order, order.substring(0, 6), product, order.substring(0, 6),
                        LocalDate.now().plusYears(2), quantity);
    }

    /** Saves the settings with some values changed; values are raw JSON. */
    private void settings(Staff owner, Map<String, String> changes) throws Exception {
        String current = body(call(get("/api/v1/control/settings"), owner.token(), null).andExpect(status().isOk()));
        Map<String, Object> values = JsonPath.read(current, "$");
        StringBuilder json = new StringBuilder("{");
        for (String key : List.of("microCountSize", "adjustmentApprovalCents", "refundApprovalCents",
                "cancellationApprovalCents", "alertAdjustmentCents", "alertCancellationCents",
                "alertCashVarianceCents", "pinFailureAlertCount", "blindReceiving", "receivingSeparation",
                "activityHours", "webhookEnabled", "webhookUrl", "version")) {
            String value = changes.containsKey(key) ? changes.get(key) : toJson(values.get(key));
            json.append(json.length() > 1 ? "," : "").append('"').append(key).append("\":").append(value);
        }
        if (changes.containsKey("webhookSecret")) {
            json.append(",\"webhookSecret\":").append(changes.get("webhookSecret"));
        }
        call(put("/api/v1/control/settings"), owner.token(), json.append('}').toString())
                .andExpect(status().isOk());
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : list) {
                out.append(out.length() > 1 ? "," : "").append(toJson(item));
            }
            return out.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            map.forEach((k, v) -> out.append(out.length() > 1 ? "," : "").append('"').append(k).append("\":")
                    .append(toJson(v)));
            return out.append('}').toString();
        }
        return value.toString();
    }

    /** Activity hours that do not include the current time, every day of the week. */
    private static String closedNow() {
        int hour = LocalTime.now(NIAMEY).getHour();
        String open = hour < 12 ? "20:00" : "02:00";
        String close = hour < 12 ? "21:00" : "03:00";
        StringBuilder days = new StringBuilder("[");
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            days.append(days.length() > 1 ? "," : "")
                    .append("{\"day\":\"").append(day).append("\",\"open\":\"").append(open)
                    .append("\",\"close\":\"").append(close).append("\"}");
        }
        return days.append(']').toString();
    }

    private <T> T inTenant(Pharmacy pharmacy, Supplier<T> action) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("test", new TenantId(pharmacy.tenantId())));
        return TenantContext.call(context, action);
    }

    private Map<String, Object> outbox(Pharmacy pharmacy) {
        return inTenant(pharmacy, () -> tx.execute(status -> jdbc.sql("""
                        SELECT status, attempts::int AS attempts, last_error, next_attempt_at
                        FROM control_webhook_outbox""")
                .query((rs, rowNum) -> Map.<String, Object>of("status", rs.getString("status"),
                        "attempts", rs.getInt("attempts"),
                        "last_error", rs.getString("last_error") == null ? "" : rs.getString("last_error"),
                        "next_attempt_at", rs.getTimestamp("next_attempt_at")))
                .single()));
    }

    private long countIn(Pharmacy pharmacy, String table) {
        return inTenant(pharmacy, () -> tx.execute(status -> jdbc.sql("SELECT count(*) FROM " + table)
                .query(Long.class).single()));
    }

    private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
