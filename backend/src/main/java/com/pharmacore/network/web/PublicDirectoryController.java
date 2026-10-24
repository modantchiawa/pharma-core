package com.pharmacore.network.web;

import com.pharmacore.network.application.PublicDirectoryService;
import com.pharmacore.network.application.PublicDirectoryService.ProductAvailability;
import com.pharmacore.network.application.PublicDirectoryService.PublicGuardSlot;
import com.pharmacore.network.application.PublicDirectoryService.PublicPharmacy;
import com.pharmacore.network.application.PublicRateLimiter;
import com.pharmacore.network.application.RateLimitExceededException;
import com.pharmacore.shared.web.BusinessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient-facing directory, no authentication: which pharmacies are listed, where a product can
 * be found tonight, who is on duty. Rate limited per client address.
 */
@RestController
@RequestMapping("/api/public/directory")
class PublicDirectoryController {

    private final PublicDirectoryService directory;
    private final PublicRateLimiter rateLimiter;
    private final Clock clock;

    PublicDirectoryController(PublicDirectoryService directory, PublicRateLimiter rateLimiter, Clock clock) {
        this.directory = directory;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @GetMapping("/pharmacies")
    List<PublicPharmacy> pharmacies(@RequestParam(required = false) String city,
                                    HttpServletRequest request) {
        throttle(request);
        bounded(city, "city");
        return directory.pharmacies(city);
    }

    @GetMapping("/availability")
    List<ProductAvailability> availability(@RequestParam String q,
                                           @RequestParam(required = false) String city,
                                           HttpServletRequest request) {
        throttle(request);
        bounded(q, "q");
        bounded(city, "city");
        return directory.availability(q, city);
    }

    @GetMapping("/guards")
    List<PublicGuardSlot> guards(@RequestParam(required = false) String city,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at,
                                 HttpServletRequest request) {
        throttle(request);
        bounded(city, "city");
        return directory.guards(city, at == null ? clock.instant() : at);
    }

    @GetMapping("/guards/share-text")
    Map<String, String> guardsShareText(@RequestParam(required = false) String city,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                        HttpServletRequest request) {
        throttle(request);
        bounded(city, "city");
        LocalDate day = date == null ? LocalDate.ofInstant(clock.instant(), PublicDirectoryService.DEFAULT_ZONE) : date;
        return Map.of("date", day.toString(), "text", directory.guardsShareText(city, day));
    }

    private static final int MAX_INPUT = 120;

    private static void bounded(String value, String field) {
        if (value != null && value.length() > MAX_INPUT) {
            throw new BusinessRuleException("input-too-long", field + " : 120 caract\u00e8res au maximum");
        }
    }

    private void throttle(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(clientAddress(request))) {
            throw new RateLimitExceededException("Trop de recherches, réessayez dans une minute");
        }
    }

    /** First hop of X-Forwarded-For when a reverse proxy fronts the cloud, else the socket address. */
    static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 64) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
