package com.pharmacore.printing.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything a ticket or an invoice shows, independent of the printer. Built by the application
 * from a recorded sale (or from a terminal's own basket before the sale is synchronized) and
 * rendered by {@link EscPosRenderer} or {@link TextReceiptRenderer}.
 *
 * @param header         the pharmacy identity and legal mentions
 * @param ticketNumber   ticket (and invoice) number
 * @param issuedAt       local date and time of the sale
 * @param terminalId     cash register
 * @param cashier        seller's display name
 * @param customer       customer block, null for an anonymous sale
 * @param lines          sold items in ticket order
 * @param discountCents  total of line discounts
 * @param netCents       total due, taxes included
 * @param payments       how it was paid
 * @param changeDueCents change handed back
 * @param vat            VAT breakdown by rate
 * @param certification  fiscal certification mentions, null when the sale is not certified
 * @param offline        recorded while the terminal was offline
 * @param cancelled      the sale was cancelled: the print carries a clear mention
 * @param currency       ISO code used to format amounts (XOF has no decimals)
 */
public record ReceiptDocument(Header header,
                              String ticketNumber,
                              LocalDateTime issuedAt,
                              String terminalId,
                              String cashier,
                              Customer customer,
                              List<Line> lines,
                              long discountCents,
                              long netCents,
                              List<Payment> payments,
                              long changeDueCents,
                              List<VatRow> vat,
                              Certification certification,
                              boolean offline,
                              boolean cancelled,
                              String currency) {

    public ReceiptDocument {
        lines = List.copyOf(lines);
        payments = List.copyOf(payments);
        vat = List.copyOf(vat);
        if (ticketNumber == null || ticketNumber.isBlank()) {
            throw new IllegalArgumentException("A receipt needs a ticket number");
        }
        if (header == null || issuedAt == null) {
            throw new IllegalArgumentException("A receipt needs a header and a date");
        }
        if (currency == null || currency.isBlank()) {
            currency = "XOF";
        }
    }

    public record Header(String displayName, String addressLine, String phone, String legalIdentifier,
                         String receiptHeader, String receiptFooter) {
    }

    public record Customer(String displayName, String phone, String address, String policy) {
    }

    public record Line(String designation, int quantity, long unitPriceCents, long discountCents, long netCents,
                       BigDecimal vatRate) {
    }

    public record Payment(String label, long amountCents) {
    }

    public record VatRow(BigDecimal rate, long totalTtcCents, long vatCents) {
    }

    public record Certification(String code, String qrPayload) {
    }

    public long grossCents() {
        return netCents + discountCents;
    }
}
