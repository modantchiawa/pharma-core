package com.pharmacore.sales.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pharmacore.sales.application.SaleManagementService;
import com.pharmacore.sales.application.SaleRecordingService;
import com.pharmacore.sales.application.SaleSyncService;
import com.pharmacore.sales.application.SupervisorApprovalService;
import com.pharmacore.sales.application.TerminalHeartbeatService;
import com.pharmacore.sales.domain.SaleAnomaly;
import com.pharmacore.sales.domain.SaleOrigin;
import com.pharmacore.sales.domain.SaleOutcome;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.web.SaleRequests.HeartbeatRequest;
import com.pharmacore.sales.web.SaleRequests.ReasonRequest;
import com.pharmacore.sales.web.SaleRequests.SaleRequest;
import com.pharmacore.sales.web.SaleRequests.SyncBatchRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SaleController {

    private final SaleRecordingService recording;
    private final SaleSyncService sync;
    private final SaleManagementService management;
    private final TerminalHeartbeatService heartbeats;

    SaleController(SaleRecordingService recording, SaleSyncService sync, SaleManagementService management,
                   TerminalHeartbeatService heartbeats) {
        this.recording = recording;
        this.sync = sync;
        this.management = management;
        this.heartbeats = heartbeats;
    }

    /** Live sale: rules enforced, 201 when stored, 200 when already stored (idempotent retry). */
    @PostMapping("/api/v1/sales")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    ResponseEntity<OutcomeResponse> record(@Valid @RequestBody SaleRequest request) {
        SaleOutcome outcome = recording.record(request.toDraft(SaleOrigin.ONLINE));
        HttpStatus status = switch (outcome) {
            case SaleOutcome.Accepted _ -> HttpStatus.CREATED;
            case SaleOutcome.Duplicate _ -> HttpStatus.OK;
            case SaleOutcome.Rejected _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case SaleOutcome.Retry _ -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(OutcomeResponse.of(outcome));
    }

    /** Offline journal replay: always 200 with one outcome per sale. */
    @PostMapping("/api/v1/pos/sync/sales")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    SyncResponse synchronize(@Valid @RequestBody SyncBatchRequest request) {
        var outcomes = sync.synchronize(request.sales().stream()
                .map(sale -> sale.toDraft(SaleOrigin.OFFLINE_REPLAY))
                .toList());
        return new SyncResponse(Instant.now(), outcomes.stream().map(OutcomeResponse::of).toList());
    }

    @PostMapping("/api/v1/pos/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        heartbeats.beat(request.terminalId(), request.appVersion(), request.pendingSales(), request.lastSyncAt(),
                request.rejectedSales());
    }

    @GetMapping("/api/v1/sales")
    @PreAuthorize("hasAuthority('SALE_VIEW')")
    List<SaleSummary> period(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                             @RequestParam(defaultValue = "100") int limit) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(1, ChronoUnit.DAYS) : from;
        return management.period(start, end, limit).stream().map(SaleSummary::of).toList();
    }

    @GetMapping("/api/v1/sales/{saleId}")
    @PreAuthorize("hasAuthority('SALE_VIEW')")
    SaleDetail get(@PathVariable UUID saleId) {
        return SaleDetail.of(management.get(saleId));
    }

    @PostMapping("/api/v1/sales/{saleId}/cancellation")
    @PreAuthorize("hasAuthority('SALE_CANCEL')")
    SaleSummary cancel(@PathVariable UUID saleId, @Valid @RequestBody ReasonRequest request) {
        return SaleSummary.of(management.cancel(saleId, request.reason(), request.terminalId(),
                new SupervisorApprovalService.SupervisorApproval(request.supervisorEmail(), request.supervisorPin())));
    }

    @GetMapping("/api/v1/sales/review-queue")
    @PreAuthorize("hasAuthority('SALE_REVIEW')")
    List<SaleSummary> reviewQueue(@RequestParam(defaultValue = "50") int limit) {
        return management.reviewQueue(limit).stream().map(SaleSummary::of).toList();
    }

    @PostMapping("/api/v1/sales/{saleId}/review")
    @PreAuthorize("hasAuthority('SALE_REVIEW')")
    SaleSummary review(@PathVariable UUID saleId, @Valid @RequestBody ReasonRequest request) {
        return SaleSummary.of(management.review(saleId, request.reason()));
    }

    record SyncResponse(Instant serverTime, List<OutcomeResponse> outcomes) {
    }

    /** Flat wire form of the sealed {@link SaleOutcome}, discriminated by {@code type}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OutcomeResponse(String type, UUID clientSaleId, UUID saleId, Long ordonnancierNumber,
                           Set<SaleAnomaly> anomalies, String code, Set<SaleAnomaly> violations, String message) {

        static OutcomeResponse of(SaleOutcome outcome) {
            return switch (outcome) {
                case SaleOutcome.Accepted a -> new OutcomeResponse("ACCEPTED", a.clientSaleId(), a.saleId(),
                        a.ordonnancierNumber(), a.anomalies(), null, null, null);
                case SaleOutcome.Duplicate d -> new OutcomeResponse("DUPLICATE", d.clientSaleId(), d.saleId(),
                        null, null, null, null, null);
                case SaleOutcome.Rejected r -> new OutcomeResponse("REJECTED", r.clientSaleId(), null, null, null,
                        r.code(), r.violations(), r.message());
                case SaleOutcome.Retry r -> new OutcomeResponse("RETRY", r.clientSaleId(), null, null, null, null,
                        null, r.message());
            };
        }
    }

    record SaleSummary(UUID id, UUID clientSaleId, String ticketNumber, String terminalId, SaleOrigin origin,
                       SaleEntity.Status status, Instant occurredAt, long totalNetCents, boolean reviewRequired,
                       Set<SaleAnomaly> anomalies, Long ordonnancierNumber, UUID customerId,
                       String certificationStatus, String certificationCode, String certificationQr) {

        static SaleSummary of(SaleEntity sale) {
            return new SaleSummary(sale.id(), sale.clientSaleId(), sale.ticketNumber(), sale.terminalId(),
                    sale.origin(), sale.status(), sale.occurredAt(), sale.totalNetCents(), sale.reviewRequired(),
                    sale.anomalies(), sale.ordonnancierNumber(), sale.customerId(),
                    sale.certificationStatus().name(), sale.certificationCode(), sale.certificationQr());
        }
    }

    record SaleDetail(SaleSummary summary, long totalGrossCents, long totalDiscountCents, List<Line> lines,
                      List<Payment> payments, String cancelReason) {

        static SaleDetail of(SaleEntity sale) {
            return new SaleDetail(SaleSummary.of(sale), sale.totalGrossCents(), sale.totalDiscountCents(),
                    sale.lines().stream().map(line -> new Line(line.lineNo(), line.productId(), line.quantity(),
                            line.unitPriceCents(), line.discountCents(), line.netCents(), line.vatRate())).toList(),
                    sale.payments().stream().map(payment -> new Payment(payment.method().name(),
                            payment.amountCents(), payment.thirdPartyCode(), payment.provider(),
                            payment.confirmationStatus().name())).toList(),
                    sale.cancelReason());
        }
    }

    record Line(short lineNo, UUID productId, int quantity, long unitPriceCents, long discountCents, long netCents,
                BigDecimal vatRate) {
    }

    record Payment(String method, long amountCents, String thirdPartyCode, String provider, String confirmationStatus) {
    }
}
