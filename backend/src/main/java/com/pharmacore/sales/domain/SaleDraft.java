package com.pharmacore.sales.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A sale as captured by the POS terminal. {@code clientSaleId} is generated on the terminal
 * (UUID v7) and makes every replay idempotent.
 */
public record SaleDraft(UUID clientSaleId,
                        String terminalId,
                        String ticketNumber,
                        Instant occurredAt,
                        SaleOrigin origin,
                        UUID cashierUserId,
                        UUID customerId,
                        List<Line> lines,
                        List<Payment> payments,
                        Prescription prescription,
                        List<SafetyOverride> safetyOverrides) {

    public SaleDraft(UUID clientSaleId, String terminalId, String ticketNumber, Instant occurredAt, SaleOrigin origin,
                     UUID cashierUserId, UUID customerId, List<Line> lines, List<Payment> payments,
                     Prescription prescription) {
        this(clientSaleId, terminalId, ticketNumber, occurredAt, origin, cashierUserId, customerId, lines, payments,
                prescription, List.of());
    }

    public SaleDraft {
        Objects.requireNonNull(clientSaleId, "clientSaleId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(origin, "origin");
        lines = List.copyOf(lines);
        payments = List.copyOf(payments);
        safetyOverrides = safetyOverrides == null ? List.of() : List.copyOf(safetyOverrides);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A sale needs at least one line");
        }
    }

    /** @param posology how the patient takes it, as typed at the counter (optional) */
    public record Line(UUID productId, int quantity, long unitPriceCents, long discountCents, Posology posology) {

        public Line(UUID productId, int quantity, long unitPriceCents, long discountCents) {
            this(productId, quantity, unitPriceCents, discountCents, null);
        }

        public Line {
            if (quantity <= 0) {
                throw new IllegalArgumentException("Line quantity must be positive");
            }
            if (unitPriceCents < 0 || discountCents < 0) {
                throw new IllegalArgumentException("Amounts cannot be negative");
            }
            if (discountCents > grossCents(quantity, unitPriceCents)) {
                throw new IllegalArgumentException("Discount exceeds line amount");
            }
        }

        public long grossCents() {
            return grossCents(quantity, unitPriceCents);
        }

        public long netCents() {
            return grossCents() - discountCents;
        }

        private static long grossCents(int quantity, long unitPriceCents) {
            return Math.multiplyExact(unitPriceCents, (long) quantity);
        }
    }

    /**
     * @param provider mobile money operator or card scheme, free text matched against the configured list
     * @param policyId insurance policy covering a {@link PaymentMethod#THIRD_PARTY_PAYER} share
     */
    public record Payment(PaymentMethod method, long amountCents, String thirdPartyCode, String reference,
                          String provider, UUID policyId) {

        public Payment {
            Objects.requireNonNull(method, "method");
            if (amountCents <= 0) {
                throw new IllegalArgumentException("Payment amount must be positive");
            }
            if (policyId != null && method != PaymentMethod.THIRD_PARTY_PAYER) {
                throw new IllegalArgumentException("Only a third-party payer share references a policy");
            }
        }

        public Payment(PaymentMethod method, long amountCents, String thirdPartyCode, String reference,
                       String provider) {
            this(method, amountCents, thirdPartyCode, reference, provider, null);
        }

        public boolean coveredByPolicy() {
            return method == PaymentMethod.THIRD_PARTY_PAYER && policyId != null;
        }
    }

    /** @param prescriptionId renewable prescription on file the dispensing is attached to (optional) */
    public record Prescription(String prescriberName, String prescriberRpps, String patientReference,
                               UUID prescriptionId) {

        public Prescription(String prescriberName, String prescriberRpps, String patientReference) {
            this(prescriberName, prescriberRpps, patientReference, null);
        }
    }

    /**
     * The pharmacist's decision to dispense despite a contraindication found at the counter:
     * {@code alertKey} is the stable key of the alert, {@code reason} what they wrote.
     */
    public record SafetyOverride(String alertKey, String reason) {

        public SafetyOverride {
            Objects.requireNonNull(alertKey, "alertKey");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("An override needs a reason");
            }
            reason = reason.trim();
        }
    }

    public long grossCents() {
        return lines.stream().mapToLong(Line::grossCents).sum();
    }

    public long discountCents() {
        return lines.stream().mapToLong(Line::discountCents).sum();
    }

    public long netCents() {
        return grossCents() - discountCents();
    }

    public long paidCents() {
        return payments.stream().mapToLong(Payment::amountCents).sum();
    }

    public long creditCents() {
        return payments.stream().filter(p -> p.method().isCustomerAccount()).mapToLong(Payment::amountCents).sum();
    }

    public boolean onCredit() {
        return creditCents() > 0;
    }

    /** Third-party payer share declared for each policy referenced by the payments. */
    public Map<UUID, Long> insuredSharesByPolicy() {
        return payments.stream().filter(Payment::coveredByPolicy)
                .collect(Collectors.groupingBy(Payment::policyId, LinkedHashMap::new,
                        Collectors.summingLong(Payment::amountCents)));
    }

    public UUID prescriptionId() {
        return prescription == null ? null : prescription.prescriptionId();
    }
}
