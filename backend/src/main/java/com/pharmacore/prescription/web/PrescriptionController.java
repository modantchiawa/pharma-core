package com.pharmacore.prescription.web;

import com.pharmacore.catalog.domain.DrugListing;
import com.pharmacore.prescription.application.DispensingRegisterService;
import com.pharmacore.prescription.application.DispensingRegisterService.RegisterEntry;
import com.pharmacore.prescription.application.PrescriptionService;
import com.pharmacore.prescription.application.PrescriptionService.LineCommand;
import com.pharmacore.prescription.application.PrescriptionService.PrescriptionView;
import com.pharmacore.prescription.persistence.PrescriptionEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Dispensing register (listed products) and renewable prescriptions on file. */
@RestController
@RequestMapping("/api/v1/prescriptions")
class PrescriptionController {

    private final PrescriptionService prescriptions;
    private final DispensingRegisterService register;

    PrescriptionController(PrescriptionService prescriptions, DispensingRegisterService register) {
        this.prescriptions = prescriptions;
        this.register = register;
    }

    // ------------------------------------------------------------------ register

    @GetMapping("/register")
    @PreAuthorize("hasAuthority('PRESCRIPTION_REGISTER_VIEW')")
    List<RegisterEntry> register(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) DrugListing listing,
                                 @RequestParam(defaultValue = "500") int limit) {
        return register.register(from, to, listing, limit);
    }

    @GetMapping(value = "/register/export", produces = "text/csv")
    @PreAuthorize("hasAuthority('PRESCRIPTION_REGISTER_VIEW')")
    ResponseEntity<byte[]> exportRegister(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) DrugListing listing) {
        String name = (listing == null ? "ordonnancier" : "registre-" + listing.name().toLowerCase()) + "-" + from + "-" + to;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"")
                .body(register.exportCsv(from, to, listing).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ renewable prescriptions

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRESCRIPTION_DISPENSE')")
    PrescriptionView create(@Valid @RequestBody PrescriptionRequest request) {
        return prescriptions.create(request.toDetails(),
                request.lines().stream().map(line -> new LineCommand(line.productId(), line.quantity(), line.posology()))
                        .toList());
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PRESCRIPTION_DISPENSE', 'PRESCRIPTION_REGISTER_VIEW', 'SALE_CREATE')")
    List<PrescriptionView> search(@RequestParam(required = false) UUID customerId,
                                  @RequestParam(required = false) @Size(max = 100) String q,
                                  @RequestParam(required = false) PrescriptionEntity.Status status,
                                  @RequestParam(defaultValue = "50") int limit) {
        return prescriptions.search(customerId, q, status, limit);
    }

    @GetMapping("/renewals-due")
    @PreAuthorize("hasAnyAuthority('PRESCRIPTION_DISPENSE', 'PRESCRIPTION_REGISTER_VIEW')")
    List<PrescriptionView> renewalsDue(@RequestParam(defaultValue = "7") @Min(0) @Max(365) int withinDays) {
        return prescriptions.renewalsDue(withinDays);
    }

    @GetMapping("/{prescriptionId}")
    @PreAuthorize("hasAnyAuthority('PRESCRIPTION_DISPENSE', 'PRESCRIPTION_REGISTER_VIEW', 'SALE_CREATE')")
    PrescriptionView get(@PathVariable UUID prescriptionId) {
        return prescriptions.get(prescriptionId);
    }

    @PostMapping("/{prescriptionId}/dispensing")
    @PreAuthorize("hasAuthority('PRESCRIPTION_DISPENSE')")
    PrescriptionView dispense(@PathVariable UUID prescriptionId, @Valid @RequestBody DispensingRequest request) {
        return prescriptions.dispense(prescriptionId, request.saleId());
    }

    @PostMapping("/{prescriptionId}/cancellation")
    @PreAuthorize("hasAuthority('PRESCRIPTION_DISPENSE')")
    PrescriptionView cancel(@PathVariable UUID prescriptionId, @Valid @RequestBody ReasonRequest request) {
        return prescriptions.cancel(prescriptionId, request.reason());
    }

    // ------------------------------------------------------------------ requests

    record PrescriptionRequest(UUID customerId,
                               @NotBlank @Size(max = 150) String prescriberName,
                               @Size(max = 30) String prescriberIdentifier,
                               @Size(max = 64) String patientReference,
                               @NotNull LocalDate issuedOn,
                               @NotNull LocalDate validUntil,
                               @Min(1) @Max(24) int renewalsAllowed,
                               @Size(max = 500) String notes,
                               @NotEmpty @Size(max = 50) List<@Valid LineRequest> lines) {

        PrescriptionEntity.Details toDetails() {
            return new PrescriptionEntity.Details(customerId, prescriberName, prescriberIdentifier, patientReference,
                    issuedOn, validUntil, renewalsAllowed, notes);
        }
    }

    record LineRequest(@NotNull UUID productId, @Positive int quantity, @Size(max = 200) String posology) {
    }

    record DispensingRequest(@NotNull UUID saleId) {
    }

    record ReasonRequest(@NotBlank @Size(max = 255) String reason) {
    }
}
