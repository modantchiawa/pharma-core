package com.pharmacore.followup.application;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Generic HTTP adapter: {@code POST {url}} with a JSON body {@code {"to","from","text"}} and the
 * pharmacy's token as a bearer. Most aggregators of the region (and any small relay the
 * pharmacy hosts) accept this shape, or map it in one line.
 */
@Component
public class HttpSmsGateway implements SmsGatewayPort {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public HttpSmsGateway(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Optional<String> send(Gateway gateway, String internationalDigits, String body) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("to", "+" + internationalDigits);
        if (gateway.sender() != null && !gateway.sender().isBlank()) {
            payload.put("from", gateway.sender());
        }
        payload.put("text", body);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gateway.url()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "PharmaCore-SMS/1")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8));
            if (gateway.token() != null && !gateway.token().isBlank()) {
                request.header("Authorization", "Bearer " + gateway.token());
            }
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2 ? Optional.empty() : Optional.of("HTTP " + response.statusCode());
        } catch (IOException | IllegalArgumentException e) {
            return Optional.of(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " : " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("Envoi interrompu");
        }
    }
}
