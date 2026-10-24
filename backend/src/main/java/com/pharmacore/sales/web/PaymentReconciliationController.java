package com.pharmacore.sales.web;

import com.pharmacore.sales.application.CashPolicyService;
import com.pharmacore.sales.application.CashPolicyService.CashPolicy;
import com.pharmacore.sales.application.PaymentReconciliationService;
import com.pharmacore.sales.application.PaymentReconciliationService.PendingPayment;
import com.pharmacore.sales.persistence.SalePaymentEntity.ConfirmationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator statement reconciliation of electronic payments, and the pharmacy's approval thresholds. */
@RestController
class PaymentReconciliationController {

    private final PaymentReconciliationService payments;
    private final CashPolicyService policy;

    PaymentReconciliationController(PaymentReconciliationService payments, CashPolicyService policy) {
        this.payments = payments;
        this.policy = policy;
    }

    @GetMapping("/api/v1/payments/pending")
    @PreAuthorize("hasAnyAuthority('PAYMENT_RECONCILE', 'CASH_SESSION_MANAGE')")
    List<PendingPayment> pending(@RequestParam(required = false) String provider,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(defaultValue = "PENDING") ConfirmationStatus status,
                                 @RequestParam(defaultValue = "200") int limit) {
        Instant end = to == null ? Instant.now().plus(1, ChronoUnit.DAYS) : to;
        Instant start = from == null ? end.minus(31, ChronoUnit.DAYS) : from;
        return payments.list(status, provider, start, end, limit);
    }

    @PostMapping("/api/v1/payments/{paymentId}/confirmation")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    PendingPayment confirm(@PathVariable UUID paymentId) {
        return payments.confirm(paymentId);
    }

    @PostMapping("/api/v1/payments/{paymentId}/rejection")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    PendingPayment reject(@PathVariable UUID paymentId, @Valid @RequestBody ReasonRequest request) {
        return payments.reject(paymentId, request.reason());
    }

    @GetMapping("/api/v1/cash/policy")
    @PreAuthorize("hasAnyAuthority('CASH_SESSION_MANAGE', 'CASH_APPROVE', 'SALE_CANCEL')")
    CashPolicy policy() {
        return policy.current();
    }

    @PutMapping("/api/v1/cash/policy")
    @PreAuthorize("hasAuthority('CASH_APPROVE')")
    CashPolicy updatePolicy(@Valid @RequestBody PolicyRequest request) {
        return policy.update(new CashPolicy(request.cashDifferenceThresholdCents(), request.cancelApprovalThresholdCents()));
    }

    record ReasonRequest(@NotBlank @Size(max = 255) String reason) {
    }

    record PolicyRequest(@PositiveOrZero long cashDifferenceThresholdCents, @PositiveOrZero long cancelApprovalThresholdCents) {
    }
}
