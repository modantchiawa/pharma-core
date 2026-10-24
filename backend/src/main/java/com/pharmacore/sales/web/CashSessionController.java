package com.pharmacore.sales.web;

import com.pharmacore.sales.application.CashSessionService;
import com.pharmacore.sales.application.CashSessionService.Movement;
import com.pharmacore.sales.application.CashSessionService.Report;
import com.pharmacore.sales.persistence.CashMovementEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cash/sessions")
class CashSessionController {

    private final CashSessionService cash;

    CashSessionController(CashSessionService cash) {
        this.cash = cash;
    }

    @GetMapping("/open")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'CASH_SESSION_MANAGE')")
    ResponseEntity<Report> open(@RequestParam String terminalId) {
        return cash.open(terminalId)
                .map(session -> ResponseEntity.ok(cash.report(session.id())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CASH_SESSION_MANAGE')")
    Report openSession(@Valid @RequestBody OpenRequest request) {
        return cash.openSession(request.terminalId(), request.openingFloatCents());
    }

    @PostMapping("/{sessionId}/closure")
    @PreAuthorize("hasAuthority('CASH_SESSION_MANAGE')")
    Report close(@PathVariable UUID sessionId, @Valid @RequestBody CloseRequest request) {
        return cash.closeSession(sessionId, request.countedCashCents(), request.notes());
    }

    @PostMapping("/{sessionId}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CASH_EXPENSE')")
    Movement movement(@PathVariable UUID sessionId, @Valid @RequestBody MovementRequest request) {
        return cash.recordMovement(sessionId, request.kind(), request.amountCents(), request.reason(),
                request.expenseAccount());
    }

    @GetMapping("/{sessionId}")
    @PreAuthorize("hasAnyAuthority('CASH_SESSION_MANAGE', 'REPORT_VIEW')")
    Report report(@PathVariable UUID sessionId) {
        return cash.report(sessionId);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('CASH_SESSION_MANAGE', 'REPORT_VIEW')")
    List<Report> recent(@RequestParam(defaultValue = "20") int limit) {
        return cash.recent(limit);
    }

    record OpenRequest(@NotBlank @Size(max = 40) String terminalId, @PositiveOrZero long openingFloatCents) {
    }

    record CloseRequest(@PositiveOrZero long countedCashCents, @Size(max = 500) String notes) {
    }

    record MovementRequest(@NotNull CashMovementEntity.Kind kind,
                           @Positive long amountCents,
                           @NotBlank @Size(max = 255) String reason,
                           @Pattern(regexp = "^6[0-9]{1,9}$") String expenseAccount) {
    }
}
