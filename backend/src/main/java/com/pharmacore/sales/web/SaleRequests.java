package com.pharmacore.sales.web;

import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.domain.Posology;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOrigin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire format shared by the online endpoint and the offline sync endpoint. */
final class SaleRequests {

    private SaleRequests() {
    }

    record SaleRequest(@NotNull UUID clientSaleId,
                       @NotBlank @Size(max = 40) String terminalId,
                       @NotBlank @Size(max = 40) String ticketNumber,
                       @NotNull Instant occurredAt,
                       UUID cashierUserId,
                       UUID customerId,
                       @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines,
                       @NotNull @Size(max = 10) List<@Valid PaymentRequest> payments,
                       @Valid PrescriptionRequest prescription,
                       @Size(max = 50) List<@Valid SafetyOverrideRequest> safetyOverrides) {

        SaleDraft toDraft(SaleOrigin origin) {
            return new SaleDraft(clientSaleId, terminalId, ticketNumber, occurredAt, origin, cashierUserId, customerId,
                    lines.stream().map(line -> new SaleDraft.Line(line.productId(), line.quantity(),
                            line.unitPriceCents(), line.discountCents(),
                            line.posology() == null ? null : line.posology().toPosology())).toList(),
                    payments.stream().map(payment -> new SaleDraft.Payment(payment.method(), payment.amountCents(),
                            payment.thirdPartyCode(), payment.reference(), payment.provider(), payment.policyId()))
                            .toList(),
                    prescription == null ? null : new SaleDraft.Prescription(prescription.prescriberName(),
                            prescription.prescriberRpps(), prescription.patientReference(),
                            prescription.prescriptionId()),
                    safetyOverrides == null ? List.of() : safetyOverrides.stream()
                            .map(o -> new SaleDraft.SafetyOverride(o.alertKey(), o.reason())).toList());
        }
    }

    /** The pharmacist dispensed despite an alert the terminal showed (see {@code /api/v1/safety/check}). */
    record SafetyOverrideRequest(@NotBlank @Size(max = 300) String alertKey,
                                 @NotBlank @Size(max = 255) String reason) {
    }

    record LineRequest(@NotNull UUID productId,
                       @Positive int quantity,
                       @PositiveOrZero long unitPriceCents,
                       @PositiveOrZero long discountCents,
                       @Valid PosologyRequest posology) {
    }

    /** Units at each moment of the day, duration, food, free instruction (all optional but one). */
    record PosologyRequest(@Min(0) @Max(Posology.MAX_UNITS_PER_TAKE) int morning,
                           @Min(0) @Max(Posology.MAX_UNITS_PER_TAKE) int noon,
                           @Min(0) @Max(Posology.MAX_UNITS_PER_TAKE) int evening,
                           @Min(0) @Max(Posology.MAX_UNITS_PER_TAKE) int bedtime,
                           @Min(0) @Max(Posology.MAX_DURATION_DAYS) int durationDays,
                           boolean withFood,
                           @Size(max = Posology.MAX_INSTRUCTIONS) String instructions) {

        /** An all-empty posology is treated as none rather than refused: the terminal sends what it has. */
        Posology toPosology() {
            if (morning + noon + evening + bedtime == 0 && durationDays == 0
                    && (instructions == null || instructions.isBlank())) {
                return null;
            }
            return new Posology(morning, noon, evening, bedtime, durationDays, withFood, instructions);
        }
    }

    record PaymentRequest(@NotNull PaymentMethod method,
                          @Positive long amountCents,
                          @Size(max = 20) String thirdPartyCode,
                          @Size(max = 64) String reference,
                          @Size(max = 30) String provider,
                          UUID policyId) {
    }

    record PrescriptionRequest(@Size(max = 150) String prescriberName,
                               @Pattern(regexp = "^[0-9]{11}$") String prescriberRpps,
                               @Size(max = 64) String patientReference,
                               UUID prescriptionId) {
    }

    record SyncBatchRequest(@NotEmpty @Size(max = 200) List<@Valid SaleRequest> sales) {
    }

    record HeartbeatRequest(@NotBlank @Size(max = 40) String terminalId,
                            @NotBlank @Size(max = 40) String appVersion,
                            @PositiveOrZero int pendingSales,
                            Instant lastSyncAt,
                            @PositiveOrZero Integer rejectedSales) {
    }

    /**
     * Supervisor credentials are only needed when the operation exceeds the pharmacy's thresholds;
     * the same pair carries the second person's signature when four eyes apply.
     */
    record ReasonRequest(@NotBlank @Size(max = 255) String reason,
                         @Size(max = 40) String terminalId,
                         @Size(max = 200) String supervisorEmail,
                         @Size(max = 12) String supervisorPin) {
    }

    record ReturnLineRequest(short lineNo, @Positive int quantity) {
    }

    /** The approver's e-mail and PIN are only needed above the four-eyes threshold or for a narcotic. */
    record ReturnRequest(@NotEmpty @Size(max = 200) List<@Valid ReturnLineRequest> lines,
                         @NotBlank @Size(max = 255) String reason,
                         @Size(max = 40) String terminalId,
                         @Size(max = 200) String approverEmail,
                         @Size(max = 12) String approverPin) {
    }
}
