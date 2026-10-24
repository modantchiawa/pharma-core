package com.pharmacore.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pharmacore.support.IntegrationTest;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Printer settings per register and ESC/POS printing of a sale towards a (fake) network printer. */
class PrintingIT extends IntegrationTest {

    @Test
    void registerPrintsItsTicketsOnTheConfiguredThermalPrinter() throws Exception {
        var pharmacy = onboardPharmacy("Impression");
        UUID product = createProduct("IBUPROFENE 400MG CPR", "NONE", 1_500);
        receiveLot(pharmacy, product, "I-1", "2028-12-31", 20);
        String cashier = staff(pharmacy, roleId(pharmacy, "CAISSIER"), "caissier-impression");
        String ticket = "T-TEST-" + shortId();
        String saleId = read(call(post("/api/v1/sales"), pharmacy.ownerToken(), sale(product, ticket))
                .andExpect(status().isCreated()), "$.saleId");

        // Nothing configured: the register prints through its browser
        call(get("/api/v1/print/terminals/T-TEST"), cashier, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("BROWSER"))
                .andExpect(jsonPath("$.paper").value("MM80"))
                .andExpect(jsonPath("$.configured").value(false));
        call(post("/api/v1/print/receipts/" + saleId + "?terminalId=T-TEST"), cashier, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("browser-print"));

        // A fake thermal printer listens on a free port
        try (var printer = new FakePrinter()) {
            String settings = """
                    {"mode":"ESCPOS_NETWORK","paper":"MM58","address":"127.0.0.1:%d","widthChars":32,"copies":1,
                     "cut":true,"drawerKick":false,"autoPrint":true,"invoicePaper":"A4"}""".formatted(printer.port());
            call(put("/api/v1/print/terminals/T-TEST"), cashier, settings).andExpect(status().isForbidden());
            call(put("/api/v1/print/terminals/T-TEST"), pharmacy.ownerToken(), settings)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("ESCPOS_NETWORK"))
                    .andExpect(jsonPath("$.widthChars").value(32))
                    .andExpect(jsonPath("$.configured").value(true));
            // a thermal printer cannot take A4 paper
            call(put("/api/v1/print/terminals/T-TEST"), pharmacy.ownerToken(), settings.replace("MM58", "A4"))
                    .andExpect(status().isUnprocessableContent());

            call(post("/api/v1/print/receipts/" + saleId + "?terminalId=T-TEST"), cashier, null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("ESCPOS_NETWORK"))
                    .andExpect(jsonPath("$.copies").value(1));
            String printed = printer.next();
            assertThat(printed).startsWith("\u001b@").contains(ticket).contains("IBUPROFENE 400MG CPR")
                    .contains("TOTAL TTC").contains("\u001dV");
            assertThat(printed).containsPattern("Pharmacie|PHARMACIE|Impression");

            // two copies on demand
            call(post("/api/v1/print/receipts/" + saleId + "?terminalId=T-TEST&copies=2"), pharmacy.ownerToken(), null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.copies").value(2));
            assertThat(printer.next()).contains(ticket);
            assertThat(printer.next()).contains(ticket);

            // test page, plain text and raw bytes
            call(post("/api/v1/print/terminals/T-TEST/test"), pharmacy.ownerToken(), null).andExpect(status().isOk());
            assertThat(printer.next()).contains("Test d'impression").contains("Poste T-TEST").contains("32 colonnes");
            call(get("/api/v1/print/receipts/" + saleId + "/text?width=32"), cashier, null)
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Ticket " + ticket)));
            byte[] raw = call(get("/api/v1/print/receipts/" + saleId + "/escpos?terminalId=T-TEST"), cashier, null)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(raw[0]).isEqualTo((byte) 0x1b);
            assertThat(raw[1]).isEqualTo((byte) '@');

            // a basket the terminal committed offline, not yet synchronized
            call(post("/api/v1/print/tickets?terminalId=T-TEST"), cashier, """
                    {"ticketNumber":"T-TEST-OFF1","occurredAt":"%s","cashier":"Aïcha",
                     "customer":{"displayName":"Mme Hadiza","phone":"+22790000000","address":null,"policy":null},
                     "lines":[{"designation":"IBUPROFENE 400MG CPR","quantity":1,"unitPriceCents":1500,"discountCents":0,"vatRate":2.10}],
                     "discountCents":0,"netCents":1500,"payments":[{"label":"Espèces","amountCents":2000}],
                     "changeDueCents":500,"vat":[{"rate":2.10,"totalTtcCents":1500,"vatCents":31}],"offline":true}
                    """.formatted(Instant.now())).andExpect(status().isOk());
            assertThat(printer.next()).contains("T-TEST-OFF1").contains("Client Mme Hadiza").contains("Rendu")
                    .contains("hors-ligne");

            // every register of the pharmacy is listed for the manager
            List<String> terminals = JsonPath.read(call(get("/api/v1/print/terminals"), pharmacy.ownerToken(), null)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$[*].terminalId");
            assertThat(terminals).contains("T-TEST");
        }

        // A printer that is switched off does not block the counter: explicit error, browser fallback
        call(put("/api/v1/print/terminals/T-TEST"), pharmacy.ownerToken(), """
                {"mode":"ESCPOS_NETWORK","paper":"MM80","address":"127.0.0.1:1","widthChars":48,"copies":1,
                 "cut":true,"drawerKick":true,"autoPrint":true,"invoicePaper":"A4"}""").andExpect(status().isOk());
        call(post("/api/v1/print/receipts/" + saleId + "?terminalId=T-TEST"), cashier, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("printer-unavailable"));
    }

    /** Accepts connections like a raw-port thermal printer and keeps what each one sent. */
    private static final class FakePrinter implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0);
        private final BlockingQueue<byte[]> jobs = new LinkedBlockingQueue<>();
        private final Thread acceptor;

        FakePrinter() throws IOException {
            acceptor = Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        jobs.add(socket.getInputStream().readAllBytes());
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        String next() throws InterruptedException {
            byte[] job = jobs.poll(10, TimeUnit.SECONDS);
            assertThat(job).as("a print job reached the printer").isNotNull();
            return new String(job, StandardCharsets.ISO_8859_1);
        }

        @Override
        public void close() throws IOException {
            server.close();
            acceptor.interrupt();
        }
    }

    private String staff(Pharmacy pharmacy, String roleId, String name) throws Exception {
        String email = name + "-" + pharmacy.slug() + "@example.org";
        var registered = call(post("/api/v1/users"), pharmacy.ownerToken(), """
                {"email":"%s","displayName":"%s","roleIds":["%s"]}""".formatted(email, name, roleId))
                .andExpect(status().isCreated());
        return login(pharmacy.slug(), email, read(registered, "$.temporaryPassword"));
    }

    private String roleId(Pharmacy pharmacy, String code) throws Exception {
        var roles = call(get("/api/v1/roles"), pharmacy.ownerToken(), null).andExpect(status().isOk());
        return JsonPath.<List<String>>read(roles.andReturn().getResponse().getContentAsString(),
                "$[?(@.code == '" + code + "')].id").getFirst();
    }

    private static String sale(UUID product, String ticket) {
        return """
                {"clientSaleId":"%s","terminalId":"T-TEST","ticketNumber":"%s","occurredAt":"%s",
                 "lines":[{"productId":"%s","quantity":2,"unitPriceCents":1500,"discountCents":0}],
                 "payments":[{"method":"CASH","amountCents":3000}]}
                """.formatted(UUID.randomUUID(), ticket, Instant.now(), product);
    }
}
