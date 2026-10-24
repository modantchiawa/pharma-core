package com.pharmacore.sales.web;

import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOrigin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
                       @Valid PrescriptionRequest prescription) {

        SaleDraft toDraft(SaleOrigin origin) {
            return new SaleDraft(clientSaleId, terminalId, ticketNumber, occurredAt, origin, cashierUserId, customerId,
                    lines.stream().map(line -> new SaleDraft.Line(line.productId(), line.quantity(),
                            line.unitPriceCents(), line.discountCents())).toList(),
                    payments.stream().map(payment -> new SaleDraft.Payment(payment.method(), payment.amountCents(),
                            payment.thirdPartyCode(), payment.reference(), payment.provider(), payment.policyId()))
                            .toList(),
                    prescription == null ? null : new SaleDraft.Prescription(prescription.prescriberName(),
                            prescription.prescriberRpps(), prescription.patientReference(),
                            prescription.prescriptionId()));
        }
    }

    record LineRequest(@NotNull UUID productId,
                       @Positive int quantity,
                       @PositiveOrZero long unitPriceCents,
                       @PositiveOrZero long discountCents) {
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
                            Instant lastSyncAt) {
    }

    record ReasonRequest(@NotBlank @Size(max = 255) String reason, @Size(max = 40) String terminalId) {
    }
}
