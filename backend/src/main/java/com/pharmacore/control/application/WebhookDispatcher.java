package com.pharmacore.control.application;

import com.pharmacore.control.domain.WebhookSignature;
import com.pharmacore.control.persistence.WebhookOutboxRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the webhook outbox of the current pharmacy. Due rows are leased in a short
 * transaction (locked rows of a concurrent dispatcher are skipped), posted outside any
 * transaction, then marked delivered or rescheduled with exponential backoff. A pharmacy offline
 * for a while simply delivers its backlog, oldest first, when the Internet is back.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int BATCH = 20;

    private final WebhookOutboxRepository outbox;
    private final ControlSettingsService settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WebhookDispatcher(WebhookOutboxRepository outbox, ControlSettingsService settings,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.outbox = outbox;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    private record Claimed(UUID id, String payload) {
    }

    /** @return how many deliveries succeeded */
    public int deliverDue() {
        var effective = tx.execute(status -> settings.effective());
        if (effective == null || !effective.webhookActive()) {
            return 0;
        }
        List<Claimed> claimed = tx.execute(status -> {
            Instant now = clock.instant();
            return outbox.lockDue(now, PageRequest.of(0, BATCH)).stream()
                    .map(row -> {
                        row.lease(now.plus(LEASE));
                        return new Claimed(row.id(), row.payload());
                    })
                    .toList();
        });
        int delivered = 0;
        for (Claimed row : claimed == null ? List.<Claimed>of() : claimed) {
            Optional<String> error = post(effective.values().webhookUrl(), effective.webhookSecret(), row);
            tx.executeWithoutResult(status -> outbox.findById(row.id()).ifPresent(entity -> {
                Instant now = clock.instant();
                if (error.isEmpty()) {
                    entity.delivered(now);
                } else {
                    entity.failed(error.get(), now);
                }
            }));
            if (error.isEmpty()) {
                delivered++;
            } else {
                log.warn("Webhook d'alerte non livré ({}) : {}", row.id(), error.get());
            }
        }
        return delivered;
    }

    /** @return the failure, or empty when the receiver answered 2xx */
    private Optional<String> post(String url, String secret, Claimed row) {
        long timestamp = clock.instant().getEpochSecond();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "PharmaCore-Alerts/1")
                    .header(WebhookSignature.EVENT_HEADER, "control.alert")
                    .header("X-PharmaCore-Delivery", row.id().toString())
                    .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, timestamp, row.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(row.payload(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2 ? Optional.empty()
                    : Optional.of("HTTP " + response.statusCode());
        } catch (IOException | IllegalArgumentException e) {
            return Optional.of(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " : " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("Envoi interrompu");
        }
    }
}
