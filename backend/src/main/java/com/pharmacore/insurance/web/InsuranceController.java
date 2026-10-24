package com.pharmacore.insurance.web;

import com.pharmacore.insurance.application.InsuranceStatementService;
import com.pharmacore.insurance.application.InsuranceStatementService.Receivable;
import com.pharmacore.insurance.application.InsuranceStatementService.StatementDetail;
import com.pharmacore.insurance.application.InsuranceStatementService.StatementView;
import com.pharmacore.insurance.application.InsurerService;
import com.pharmacore.insurance.application.InsurerService.InsurerView;
import com.pharmacore.insurance.application.InsurerService.PolicyView;
import com.pharmacore.insurance.domain.InsurerKind;
import com.pharmacore.insurance.persistence.InsurancePolicyEntity;
import com.pharmacore.insurance.persistence.InsurerEntity;
import com.pharmacore.sales.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Third-party payers: insurers, customer policies, statements and receivables. */
@RestController
class InsuranceController {

    private final InsurerService insurers;
    private final InsuranceStatementService statements;

    InsuranceController(InsurerService insurers, InsuranceStatementService statements) {
        this.insurers = insurers;
        this.statements = statements;
    }

    // ------------------------------------------------------------------ insurers

    @GetMapping("/api/v1/insurers")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE')")
    List<InsurerView> list() {
        return insurers.list();
    }

    @GetMapping("/api/v1/insurers/{insurerId}")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE')")
    InsurerView get(@PathVariable UUID insurerId) {
        return insurers.get(insurerId);
    }

    @PostMapping("/api/v1/insurers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    InsurerView create(@Valid @RequestBody InsurerRequest request) {
        return insurers.create(request.toDetails());
    }

    @PutMapping("/api/v1/insurers/{insurerId}")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    InsurerView update(@PathVariable UUID insurerId, @Valid @RequestBody InsurerRequest request) {
        return insurers.update(insurerId, request.toDetails());
    }

    @PutMapping("/api/v1/insurers/{insurerId}/activation")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    InsurerView activation(@PathVariable UUID insurerId, @Valid @RequestBody ActivationRequest request) {
        return insurers.setActive(insurerId, request.active());
    }

    // ------------------------------------------------------------------ policies

    @GetMapping("/api/v1/insurers/{insurerId}/policies")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE')")
    List<PolicyView> policies(@PathVariable UUID insurerId) {
        return insurers.policiesOfInsurer(insurerId);
    }

    @PostMapping("/api/v1/insurers/{insurerId}/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    PolicyView createPolicy(@PathVariable UUID insurerId, @Valid @RequestBody PolicyRequest request) {
        return insurers.createPolicy(insurerId, request.customerId(), request.toDetails());
    }

    @PutMapping("/api/v1/insurance/policies/{policyId}")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    PolicyView updatePolicy(@PathVariable UUID policyId, @Valid @RequestBody PolicyRequest request) {
        return insurers.updatePolicy(policyId, request.toDetails());
    }

    /** The counter asks which covers apply to the customer in front of it. */
    @GetMapping("/api/v1/customers/{customerId}/policies")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE', 'SALE_CREATE')")
    List<PolicyView> customerPolicies(@PathVariable UUID customerId) {
        return insurers.usablePoliciesOfCustomer(customerId);
    }

    // ------------------------------------------------------------------ statements

    @PostMapping("/api/v1/insurers/{insurerId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    StatementView generate(@PathVariable UUID insurerId, @Valid @RequestBody PeriodRequest request) {
        return statements.generate(insurerId, request.periodFrom(), request.periodTo());
    }

    @GetMapping("/api/v1/insurers/{insurerId}/statements")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE')")
    List<StatementView> statements(@PathVariable UUID insurerId) {
        return statements.list(insurerId);
    }

    @GetMapping("/api/v1/insurance/statements/{statementId}")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE')")
    StatementDetail statement(@PathVariable UUID statementId) {
        return statements.get(statementId);
    }

    @GetMapping(value = "/api/v1/insurance/statements/{statementId}/export", produces = "text/csv")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE')")
    ResponseEntity<byte[]> export(@PathVariable UUID statementId) {
        var detail = statements.get(statementId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"" + detail.statement().number() + ".csv\"")
                .body(statements.export(statementId).getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/api/v1/insurance/statements/{statementId}/issue")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    StatementView issue(@PathVariable UUID statementId) {
        return statements.issue(statementId);
    }

    @PostMapping("/api/v1/insurance/statements/{statementId}/payment")
    @PreAuthorize("hasAuthority('INSURANCE_MANAGE')")
    StatementView pay(@PathVariable UUID statementId, @Valid @RequestBody SettlementRequest request) {
        return statements.recordPayment(statementId, request.amountCents(), request.method(), request.provider(),
                request.reference());
    }

    @GetMapping("/api/v1/insurance/receivables")
    @PreAuthorize("hasAnyAuthority('INSURANCE_VIEW', 'INSURANCE_MANAGE', 'ACCOUNTING_VIEW')")
    List<Receivable> receivables() {
        return statements.receivables();
    }

    // ------------------------------------------------------------------ requests

    record InsurerRequest(@NotBlank @Size(max = 150) String name,
                          @NotNull InsurerKind kind,
                          @Min(0) @Max(100) int defaultCoverageRate,
                          @Size(max = 150) String contactName,
                          @Size(max = 30) String phone,
                          @Email @Size(max = 200) String email,
                          @Size(max = 300) String address,
                          @Min(0) @Max(365) int paymentTermsDays) {

        InsurerEntity.Details toDetails() {
            return new InsurerEntity.Details(name, kind, defaultCoverageRate, contactName, phone, email, address,
                    paymentTermsDays);
        }
    }

    record ActivationRequest(boolean active) {
    }

    record PolicyRequest(UUID customerId,
                         @NotBlank @Size(max = 60) String policyNumber,
                         @NotBlank @Size(max = 150) String beneficiaryName,
                         @Min(0) @Max(100) int coverageRate,
                         @Positive Long monthlyCeilingCents,
                         @NotNull LocalDate validFrom,
                         LocalDate validUntil,
                         Boolean active) {

        InsurancePolicyEntity.Details toDetails() {
            return new InsurancePolicyEntity.Details(policyNumber, beneficiaryName, coverageRate, monthlyCeilingCents,
                    validFrom, validUntil, active == null || active);
        }
    }

    record PeriodRequest(@NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodFrom,
                         @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodTo) {
    }

    record SettlementRequest(@Positive long amountCents,
                             @NotNull PaymentMethod method,
                             @Size(max = 30) String provider,
                             @Size(max = 64) String reference) {
    }
}
