package com.pharmacore.printing.web;

import com.pharmacore.printing.application.PrintSettingsService;
import com.pharmacore.printing.application.PrinterSettings;
import com.pharmacore.printing.application.PrinterSettings.InvoicePaper;
import com.pharmacore.printing.application.PrinterSettings.Mode;
import com.pharmacore.printing.application.PrinterSettings.Paper;
import com.pharmacore.printing.application.ReceiptPrintService;
import com.pharmacore.printing.application.ReceiptPrintService.PrintResult;
import com.pharmacore.printing.application.ReceiptPrintService.TerminalTicket;
import com.pharmacore.printing.domain.ReceiptDocument.Customer;
import com.pharmacore.printing.domain.ReceiptDocument.Line;
import com.pharmacore.printing.domain.ReceiptDocument.Payment;
import com.pharmacore.printing.domain.ReceiptDocument.VatRow;
import com.pharmacore.shared.web.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/print")
class PrintController {

    private static final String TERMINAL = "^[A-Za-z0-9._-]{1,40}$";

    private final PrintSettingsService settings;
    private final ReceiptPrintService receipts;

    PrintController(PrintSettingsService settings, ReceiptPrintService receipts) {
        this.settings = settings;
        this.receipts = receipts;
    }

    @GetMapping("/terminals")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    List<PrinterSettings> terminals() {
        return settings.all();
    }

    @GetMapping("/terminals/{terminalId}")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'PRINTER_MANAGE')")
    PrinterSettings terminal(@PathVariable @Pattern(regexp = TERMINAL) String terminalId) {
        return settings.forTerminal(terminalId);
    }

    @PutMapping("/terminals/{terminalId}")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    PrinterSettings configure(@PathVariable @Pattern(regexp = TERMINAL) String terminalId,
                              @Valid @RequestBody SettingsRequest request) {
        try {
            return settings.save(new PrinterSettings(terminalId, request.mode(), request.paper(), request.address(),
                    request.widthChars(), request.copies(), request.cut(), request.drawerKick(), request.autoPrint(),
                    request.invoicePaper() == null ? InvoicePaper.A4 : request.invoicePaper(), true));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("invalid-printer-settings", e.getMessage());
        }
    }

    @PostMapping("/terminals/{terminalId}/test")
    @PreAuthorize("hasAuthority('PRINTER_MANAGE')")
    PrintResult test(@PathVariable @Pattern(regexp = TERMINAL) String terminalId) {
        return receipts.test(terminalId);
    }

    @PostMapping("/receipts/{saleId}")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'SALE_VIEW')")
    PrintResult print(@PathVariable UUID saleId, @RequestParam @Pattern(regexp = TERMINAL) String terminalId,
                      @RequestParam(required = false) @Min(1) @Max(3) Integer copies) {
        return receipts.print(saleId, terminalId, copies);
    }

    /** Ticket of a basket the terminal just committed locally (not yet synchronized). */
    @PostMapping("/tickets")
    @PreAuthorize("hasAuthority('SALE_CREATE')")
    PrintResult printTicket(@RequestParam @Pattern(regexp = TERMINAL) String terminalId,
                            @Valid @RequestBody TicketRequest request) {
        return receipts.printTicket(request.toTicket(), terminalId);
    }

    @GetMapping(value = "/receipts/{saleId}/escpos", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'SALE_VIEW')")
    ResponseEntity<byte[]> escPos(@PathVariable UUID saleId,
                                  @RequestParam @Pattern(regexp = TERMINAL) String terminalId) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"ticket-" + saleId + ".bin\"")
                .body(receipts.escPos(saleId, terminalId));
    }

    @GetMapping(value = "/receipts/{saleId}/text", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    @PreAuthorize("hasAnyAuthority('SALE_CREATE', 'SALE_VIEW')")
    String text(@PathVariable UUID saleId, @RequestParam(defaultValue = "48") int width) {
        if (width != 32 && width != 42 && width != 48) {
            throw new BusinessRuleException("invalid-width", "Largeur acceptée : 32, 42 ou 48 colonnes");
        }
        return receipts.text(saleId, width);
    }

    record SettingsRequest(@NotNull Mode mode,
                           @NotNull Paper paper,
                           @Size(max = 255) String address,
                           int widthChars,
                           int copies,
                           boolean cut,
                           boolean drawerKick,
                           boolean autoPrint,
                           InvoicePaper invoicePaper) {
    }

    record TicketRequest(@NotBlank @Size(max = 40) String ticketNumber,
                         @NotNull Instant occurredAt,
                         @NotBlank @Size(max = 150) String cashier,
                         CustomerRequest customer,
                         @NotEmpty @Valid List<LineRequest> lines,
                         @PositiveOrZero long discountCents,
                         @PositiveOrZero long netCents,
                         @NotNull @Valid List<PaymentRequest> payments,
                         @PositiveOrZero long changeDueCents,
                         @NotNull @Valid List<VatRequest> vat,
                         boolean offline) {

        TerminalTicket toTicket() {
            return new TerminalTicket(ticketNumber, occurredAt, cashier,
                    customer == null ? null : new Customer(customer.displayName(), customer.phone(), customer.address(),
                            customer.policy()),
                    lines.stream().map(l -> new Line(l.designation(), l.quantity(), l.unitPriceCents(),
                            l.discountCents(), l.unitPriceCents() * l.quantity() - l.discountCents(), l.vatRate())).toList(),
                    discountCents, netCents,
                    payments.stream().map(p -> new Payment(p.label(), p.amountCents())).toList(),
                    changeDueCents,
                    vat.stream().map(v -> new VatRow(v.rate(), v.totalTtcCents(), v.vatCents())).toList(),
                    offline);
        }
    }

    record CustomerRequest(@NotBlank @Size(max = 150) String displayName, @Size(max = 30) String phone,
                           @Size(max = 300) String address, @Size(max = 200) String policy) {
    }

    record LineRequest(@NotBlank @Size(max = 255) String designation, @Min(1) int quantity,
                       @PositiveOrZero long unitPriceCents, @PositiveOrZero long discountCents,
                       @NotNull BigDecimal vatRate) {
    }

    record PaymentRequest(@NotBlank @Size(max = 60) String label, long amountCents) {
    }

    record VatRequest(@NotNull BigDecimal rate, long totalTtcCents, long vatCents) {
    }
}
