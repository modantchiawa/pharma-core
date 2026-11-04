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
                        List<SafetyOverride> safetyOverrides,
                        VoucherHolder voucherHolder) {

    public SaleDraft(UUID clientSaleId, String terminalId, String ticketNumber, Instant occurredAt, SaleOrigin origin,
                     UUID cashierUserId, UUID customerId, List<Line> lines, List<Payment> payments,
                     Prescription prescription) {
        this(clientSaleId, terminalId, ticketNumber, occurredAt, origin, cashierUserId, customerId, lines, payments,
                prescription, List.of(), null);
    }

    public SaleDraft(UUID clientSaleId, String terminalId, String ticketNumber, Instant occurredAt, SaleOrigin origin,
                     UUID cashierUserId, UUID customerId, List<Line> lines, List<Payment> payments,
                     Prescription prescription, List<SafetyOverride> safetyOverrides) {
        this(clientSaleId, terminalId, ticketNumber, occurredAt, origin, cashierUserId, customerId, lines, payments,
                prescription, safetyOverrides, null);
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

    /**
     * @param posology         how the patient takes it, as typed at the counter (optional)
     * @param deferredQuantity units sold and paid now but handed over later, when the product
     *                         arrives (a "bon"); the stock gives only the other units
     * @param customerOrderId  special order this line collects (the units reserved at the
     *                         delivery are released to the sale)
     */
    public record Line(UUID productId, int quantity, long unitPriceCents, long discountCents, Posology posology,
                       int deferredQuantity, UUID customerOrderId) {

        public Line(UUID productId, int quantity, long unitPriceCents, long discountCents) {
            this(productId, quantity, unitPriceCents, discountCents, null);
        }

        public Line(UUID productId, int quantity, long unitPriceCents, long discountCents, Posology posology) {
            this(productId, quantity, unitPriceCents, discountCents, posology, 0, null);
        }

        public Line {
            if (quantity <= 0) {
                throw new IllegalArgumentException("Line quantity must be positive");
            }
            if (deferredQuantity < 0 || deferredQuantity > quantity) {
                throw new IllegalArgumentException("Deferred units must be between 0 and the line quantity");
            }
            if (deferredQuantity > 0 && customerOrderId != null) {
                throw new IllegalArgumentException("A line collecting a special order cannot leave units owed");
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

        /** Units the customer takes away now. */
        public int handedOverQuantity() {
            return quantity - deferredQuantity;
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

    /**
     * Who to call when the owed units arrive, for a sale without a customer file: the name and
     * phone written on the bon.
     */
    public record VoucherHolder(String name, String phone) {

        public VoucherHolder {
            name = name == null || name.isBlank() ? null : name.trim();
            phone = phone == null || phone.isBlank() ? null : phone.trim();
        }

        public boolean reachable() {
            return phone != null;
        }
    }

    /** Lines leaving units owed to the customer. */
    public boolean leavesVouchers() {
        return lines.stream().anyMatch(line -> line.deferredQuantity() > 0);
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
