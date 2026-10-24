package com.pharmacore.inventory.web;

import com.pharmacore.inventory.application.ExpiryWriteOffService;
import com.pharmacore.inventory.application.ExpiryWriteOffService.WriteOff;
import com.pharmacore.inventory.application.StockCountService;
import com.pharmacore.inventory.application.StockCountService.CountView;
import com.pharmacore.inventory.application.StockIntegrityService;
import com.pharmacore.inventory.application.StockIntegrityService.Report;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Physical counts, ledger integrity checks and expiry write-offs. */
@RestController
@RequestMapping("/api/v1/inventory")
class StockReliabilityController {

    private final StockCountService counts;
    private final StockIntegrityService integrity;
    private final ExpiryWriteOffService expiry;

    StockReliabilityController(StockCountService counts, StockIntegrityService integrity, ExpiryWriteOffService expiry) {
        this.counts = counts;
        this.integrity = integrity;
        this.expiry = expiry;
    }

    // ------------------------------------------------------------------ counts

    @GetMapping("/counts")
    @PreAuthorize("hasAnyAuthority('STOCK_COUNT', 'STOCK_COUNT_VALIDATE', 'STOCK_VIEW')")
    List<CountView> recent(@RequestParam(defaultValue = "20") int limit) {
        return counts.recent(limit);
    }

    @PostMapping("/counts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('STOCK_COUNT', 'STOCK_COUNT_VALIDATE')")
    CountView start(@Valid @RequestBody StartRequest request) {
        return counts.start(request.label(), request.location());
    }

    @GetMapping("/counts/{countId}")
    @PreAuthorize("hasAnyAuthority('STOCK_COUNT', 'STOCK_COUNT_VALIDATE', 'STOCK_VIEW')")
    CountView get(@PathVariable UUID countId) {
        return counts.get(countId);
    }

    @PostMapping("/counts/{countId}/lines/{lineId}")
    @PreAuthorize("hasAnyAuthority('STOCK_COUNT', 'STOCK_COUNT_VALIDATE')")
    CountView count(@PathVariable UUID countId, @PathVariable UUID lineId, @Valid @RequestBody QuantityRequest request) {
        return counts.count(countId, lineId, request.quantity());
    }

    @PostMapping("/counts/{countId}/scans")
    @PreAuthorize("hasAnyAuthority('STOCK_COUNT', 'STOCK_COUNT_VALIDATE')")
    CountView scan(@PathVariable UUID countId, @Valid @RequestBody ScanRequest request) {
        return counts.countLot(countId, request.productId(), request.lotNumber(), request.quantity());
    }

    @PostMapping("/counts/{countId}/validation")
    @PreAuthorize("hasAuthority('STOCK_COUNT_VALIDATE')")
    CountView validate(@PathVariable UUID countId, @RequestBody(required = false) NotesRequest request) {
        return counts.validate(countId, request == null ? null : request.notes());
    }

    @PostMapping("/counts/{countId}/cancellation")
    @PreAuthorize("hasAuthority('STOCK_COUNT_VALIDATE')")
    CountView cancel(@PathVariable UUID countId, @Valid @RequestBody ReasonRequest request) {
        return counts.cancel(countId, request.reason());
    }

    // ------------------------------------------------------------------ integrity

    @PostMapping("/integrity-checks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    Report check() {
        return integrity.check();
    }

    @GetMapping("/integrity-checks")
    @PreAuthorize("hasAnyAuthority('STOCK_ADJUST', 'STOCK_VIEW')")
    List<Report> checks(@RequestParam(defaultValue = "10") int limit) {
        return integrity.recent(limit);
    }

    // ------------------------------------------------------------------ expiry

    @PostMapping("/write-offs/run")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    List<WriteOff> writeOffNow() {
        return expiry.writeOffExpired();
    }

    @GetMapping("/write-offs")
    @PreAuthorize("hasAuthority('STOCK_VIEW')")
    List<WriteOff> writeOffs(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(90, ChronoUnit.DAYS) : from;
        return expiry.writeOffs(start, end);
    }

    record StartRequest(@NotBlank @Size(max = 150) String label, @Size(max = 50) String location) {
    }

    record QuantityRequest(@Min(0) int quantity) {
    }

    record ScanRequest(@NotNull UUID productId, @NotBlank @Size(max = 40) String lotNumber, @Min(0) int quantity) {
    }

    record NotesRequest(@Size(max = 500) String notes) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
