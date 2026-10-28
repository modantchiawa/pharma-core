package com.pharmacore.printing.application;

import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.customers.persistence.CustomerEntity;
import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.identity.persistence.AppUserEntity;
import com.pharmacore.identity.persistence.AppUserRepository;
import com.pharmacore.insurance.persistence.InsurancePolicyRepository;
import com.pharmacore.insurance.persistence.InsurerRepository;
import com.pharmacore.printing.application.PrinterPort.PrinterUnavailableException;
import com.pharmacore.printing.domain.EscPosRenderer;
import com.pharmacore.printing.domain.ReceiptDocument;
import com.pharmacore.printing.domain.ReceiptDocument.Certification;
import com.pharmacore.printing.domain.ReceiptDocument.Customer;
import com.pharmacore.printing.domain.ReceiptDocument.Header;
import com.pharmacore.printing.domain.ReceiptDocument.Line;
import com.pharmacore.printing.domain.ReceiptDocument.Payment;
import com.pharmacore.printing.domain.ReceiptDocument.VatRow;
import com.pharmacore.printing.domain.TextReceiptRenderer;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.domain.Posology;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleLineEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.application.BrandingService;
import com.pharmacore.tenant.application.BrandingService.BrandingView;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity.Branding;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the printable document of a sale and sends it to the register's printer. The server
 * side only drives ESC/POS printers; a register in browser mode prints its own HTML ticket, and a
 * register whose basket is not yet synchronized can post the document itself (the pharmacy header
 * is always completed here, never trusted from the terminal).
 */
@Service
public class ReceiptPrintService {

    private final SaleRepository sales;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final InsurancePolicyRepository policies;
    private final InsurerRepository insurers;
    private final AppUserRepository users;
    private final TenantRepository tenants;
    private final BrandingService branding;
    private final PrintSettingsService settings;
    private final AuditTrail audit;
    private final tools.jackson.databind.ObjectMapper json;

    public ReceiptPrintService(SaleRepository sales, ProductRepository products, CustomerRepository customers,
                               InsurancePolicyRepository policies, InsurerRepository insurers, AppUserRepository users,
                               TenantRepository tenants, BrandingService branding, PrintSettingsService settings,
                               AuditTrail audit, tools.jackson.databind.ObjectMapper json) {
        this.json = json;
        this.sales = sales;
        this.products = products;
        this.customers = customers;
        this.policies = policies;
        this.insurers = insurers;
        this.users = users;
        this.tenants = tenants;
        this.branding = branding;
        this.settings = settings;
        this.audit = audit;
    }

    public record PrintResult(UUID saleId, String terminalId, String mode, int copies, int bytes) {
    }

    /** A ticket sent by a terminal before its sale reached the server (offline basket). */
    public record TerminalTicket(String ticketNumber, Instant occurredAt, String cashier, Customer customer,
                                 List<Line> lines, long discountCents, long netCents, List<Payment> payments,
                                 long changeDueCents, List<VatRow> vat, boolean offline) {
    }

    /** Prints a recorded sale (first print or reprint) on the register's ESC/POS printer. */
    @Transactional
    public PrintResult print(UUID saleId, String terminalId, Integer copies) {
        PrinterSettings printer = requireEscPos(terminalId);
        ReceiptDocument document = document(load(saleId));
        int count = copies == null ? printer.copies() : Math.clamp(copies, 1, 3);
        byte[] payload = send(printer, new EscPosRenderer(printer.widthChars(), printer.cut(), printer.drawerKick())
                .render(document), count);
        audit.record("RECEIPT_PRINTED", "SALE", saleId, Map.of(
                "terminal", terminalId, "mode", printer.mode().name(), "copies", Integer.toString(count)));
        return new PrintResult(saleId, terminalId, printer.mode().name(), count, payload.length);
    }

    /** Prints a terminal's own ticket: used right after an offline sale, before synchronization. */
    @Transactional
    public PrintResult printTicket(TerminalTicket ticket, String terminalId) {
        PrinterSettings printer = requireEscPos(terminalId);
        BrandingView view = branding.current();
        ReceiptDocument document = new ReceiptDocument(header(view.branding()), ticket.ticketNumber(),
                LocalDateTime.ofInstant(ticket.occurredAt(), zone()), terminalId, ticket.cashier(), ticket.customer(),
                ticket.lines(), ticket.discountCents(), ticket.netCents(), ticket.payments(), ticket.changeDueCents(),
                ticket.vat(), null, ticket.offline(), false, view.currency());
        byte[] payload = send(printer, new EscPosRenderer(printer.widthChars(), printer.cut(), printer.drawerKick())
                .render(document), printer.copies());
        audit.record("RECEIPT_PRINTED", "TICKET", ticket.ticketNumber(), Map.of(
                "terminal", terminalId, "mode", printer.mode().name(), "copies", Integer.toString(printer.copies())));
        return new PrintResult(null, terminalId, printer.mode().name(), printer.copies(), payload.length);
    }

    /** Test page from the settings screen: connection, paper width and character set. */
    @Transactional
    public PrintResult test(String terminalId) {
        PrinterSettings printer = requireEscPos(terminalId);
        String name = branding.current().branding().displayName();
        byte[] payload = send(printer, new EscPosRenderer(printer.widthChars(), printer.cut(), false)
                .renderTest(name, terminalId), 1);
        audit.record("PRINTER_TESTED", "TERMINAL", terminalId, Map.of("mode", printer.mode().name(),
                "address", String.valueOf(printer.address())));
        return new PrintResult(null, terminalId, printer.mode().name(), 1, payload.length);
    }

    /** Raw ESC/POS bytes of a sale, for a local printing bridge or diagnostics. */
    @Transactional(readOnly = true)
    public byte[] escPos(UUID saleId, String terminalId) {
        PrinterSettings printer = settings.forTerminal(terminalId);
        return new EscPosRenderer(printer.widthChars(), printer.cut(), false).render(document(load(saleId)));
    }

    /** Plain-text ticket of a sale (preview, messaging copy). */
    @Transactional(readOnly = true)
    public String text(UUID saleId, int width) {
        return new TextReceiptRenderer(width).render(document(load(saleId)));
    }

    private PrinterSettings requireEscPos(String terminalId) {
        PrinterSettings printer = settings.forTerminal(terminalId);
        if (!printer.escPos()) {
            throw new BusinessRuleException("browser-print",
                    "Le poste " + terminalId + " imprime par le navigateur : aucune imprimante ESC/POS configurée");
        }
        return printer;
    }

    private static byte[] send(PrinterSettings printer, byte[] payload, int copies) {
        PrinterPort port = switch (printer.mode()) {
            case ESCPOS_NETWORK -> new NetworkPrinter(printer.address());
            case ESCPOS_SHARED -> new SharedPrinter(printer.address());
            case BROWSER -> throw new IllegalStateException("Browser mode has no server-side printer");
        };
        try {
            for (int i = 0; i < copies; i++) {
                port.print(payload);
            }
        } catch (PrinterUnavailableException e) {
            throw new BusinessRuleException("printer-unavailable", e.getMessage());
        }
        return payload;
    }

    /** The posology stored with the line, as printed; null when none was typed or it cannot be read. */
    private String posologySummary(String posologyJson) {
        if (posologyJson == null || posologyJson.isBlank()) {
            return null;
        }
        try {
            return json.readValue(posologyJson, Posology.class).summary();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private SaleEntity load(UUID saleId) {
        return sales.findWithLines(saleId).orElseThrow(() -> new NotFoundException("Sale", saleId));
    }

    private ReceiptDocument document(SaleEntity sale) {
        BrandingView view = branding.current();
        Map<UUID, ProductEntity> catalog = products.findByIdIn(
                        sale.lines().stream().map(SaleLineEntity::productId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProductEntity::id, Function.identity()));
        List<Line> lines = sale.lines().stream()
                .map(line -> new Line(
                        catalog.containsKey(line.productId()) ? catalog.get(line.productId()).designation()
                                : "Produit " + line.productId(),
                        line.quantity(), line.unitPriceCents(), line.discountCents(), line.netCents(), line.vatRate(),
                        posologySummary(line.posologyJson())))
                .toList();
        List<Payment> payments = sale.payments().stream().map(this::payment).toList();
        long paid = sale.payments().stream().mapToLong(SalePaymentEntity::amountCents).sum();
        long change = Math.max(0, paid - sale.totalNetCents());
        String cashier = users.findById(sale.cashierUserId()).map(AppUserEntity::displayName).orElse("—");
        Customer customer = sale.customerId() == null ? null
                : customers.findById(sale.customerId()).map(c -> customer(c, sale)).orElse(null);
        Certification certification = sale.certificationCode() == null ? null
                : new Certification(sale.certificationCode(), sale.certificationQr());
        return new ReceiptDocument(header(view.branding()), sale.ticketNumber(),
                LocalDateTime.ofInstant(sale.occurredAt(), zone()), sale.terminalId(), cashier, customer, lines,
                sale.totalDiscountCents(), sale.totalNetCents(), payments, change, vat(lines), certification,
                sale.origin().name().startsWith("OFFLINE"), sale.status() == SaleEntity.Status.CANCELLED,
                view.currency());
    }

    private Customer customer(CustomerEntity customer, SaleEntity sale) {
        String policy = sale.payments().stream()
                .filter(p -> p.policyId() != null)
                .findFirst()
                .flatMap(p -> policies.findById(p.policyId()))
                .map(p -> insurers.findById(p.insurerId()).map(i -> i.name() + " ").orElse("") + p.policyNumber())
                .orElse(null);
        return new Customer(customer.displayName(), customer.phone(), customer.details().address(), policy);
    }

    private Payment payment(SalePaymentEntity payment) {
        String label = switch (payment.method()) {
            case CASH -> "Espèces";
            case CARD -> "Carte bancaire";
            case CHECK -> "Chèque";
            case VOUCHER -> "Avoir";
            case CREDIT -> "Crédit client";
            case MOBILE_MONEY -> "Mobile money" + (payment.provider() == null ? ""
                    : " " + payment.provider().replace('_', ' ').toLowerCase());
            case THIRD_PARTY_PAYER -> "Tiers payant" + (payment.insurerId() == null ? ""
                    : insurers.findById(payment.insurerId()).map(i -> " " + i.name()).orElse(""));
        };
        return new Payment(label, payment.amountCents());
    }

    /** Same breakdown as the terminal: net per rate, VAT extracted from the tax-inclusive total. */
    static List<VatRow> vat(List<Line> lines) {
        Map<BigDecimal, Long> byRate = new LinkedHashMap<>();
        lines.stream()
                .sorted((a, b) -> a.vatRate().compareTo(b.vatRate()))
                .forEach(line -> byRate.merge(line.vatRate().stripTrailingZeros(), line.netCents(), Long::sum));
        var rows = new ArrayList<VatRow>();
        byRate.forEach((rate, total) -> {
            BigDecimal divisor = BigDecimal.ONE.add(rate.movePointLeft(2));
            long vat = total - BigDecimal.valueOf(total).divide(divisor, 0, RoundingMode.HALF_UP).longValue();
            rows.add(new VatRow(rate, total, vat));
        });
        return rows;
    }

    private static Header header(Branding b) {
        String address = Stream.of(b.addressLine1(), b.addressLine2(),
                        Stream.of(b.postalCode(), b.city()).filter(v -> v != null && !v.isBlank())
                                .collect(Collectors.joining(" ")))
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(", "));
        return new Header(b.displayName(), address, b.phone(), b.legalIdentifier(), b.receiptHeader(), b.receiptFooter());
    }

    private ZoneId zone() {
        return tenants.findById(TenantContext.requireTenant().value())
                .map(t -> ZoneId.of(t.timeZone()))
                .orElse(ZoneId.of("Africa/Niamey"));
    }

    static PaymentMethod method(String value) {
        return PaymentMethod.valueOf(value);
    }
}
