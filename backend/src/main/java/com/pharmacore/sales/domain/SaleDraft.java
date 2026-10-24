package com.pharmacore.sales.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
                        Prescription prescription) {

    public SaleDraft {
        Objects.requireNonNull(clientSaleId, "clientSaleId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(origin, "origin");
        lines = List.copyOf(lines);
        payments = List.copyOf(payments);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A sale needs at least one line");
        }
    }

    public record Line(UUID productId, int quantity, long unitPriceCents, long discountCents) {

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

    /** @param provider mobile money operator or card scheme, free text matched against the configured list */
    public record Payment(PaymentMethod method, long amountCents, String thirdPartyCode, String reference,
                          String provider) {

        public Payment {
            Objects.requireNonNull(method, "method");
            if (amountCents <= 0) {
                throw new IllegalArgumentException("Payment amount must be positive");
            }
        }
    }

    public record Prescription(String prescriberName, String prescriberRpps, String patientReference) {
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
}
