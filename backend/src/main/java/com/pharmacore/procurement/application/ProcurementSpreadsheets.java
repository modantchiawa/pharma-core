package com.pharmacore.procurement.application;

import static com.pharmacore.importing.domain.XlsxWriter.amount;
import static com.pharmacore.importing.domain.XlsxWriter.bold;
import static com.pharmacore.importing.domain.XlsxWriter.date;
import static com.pharmacore.importing.domain.XlsxWriter.empty;
import static com.pharmacore.importing.domain.XlsxWriter.number;
import static com.pharmacore.importing.domain.XlsxWriter.text;
import static com.pharmacore.importing.domain.XlsxWriter.totalAmount;

import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductRepository;
import com.pharmacore.importing.domain.ColumnMapping;
import com.pharmacore.importing.domain.ImportKind;
import com.pharmacore.importing.domain.TabularFile;
import com.pharmacore.importing.domain.TabularReaders;
import com.pharmacore.importing.domain.ValueParsers;
import com.pharmacore.importing.domain.XlsxWriter;
import com.pharmacore.importing.domain.XlsxWriter.Cell;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.procurement.application.ProcurementService.OrderLineView;
import com.pharmacore.procurement.application.ProcurementService.OrderView;
import com.pharmacore.procurement.domain.InvoiceMatching;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Excel side of purchasing: a supplier order as a workbook to send to the wholesaler, a booked
 * delivery (supplier invoice) as a workbook for the accountant, and the wholesaler's invoice or
 * delivery note read back to pre-fill the receiving form.
 */
@Service
public class ProcurementSpreadsheets {

    private static final DateTimeFormatter FRENCH_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * One line of a supplier invoice read from Excel, matched to the catalogue and to the order.
     *
     * @param row         line of the file (1 = first data row)
     * @param orderLineNo line of the order it delivers, null when the product was not ordered
     * @param issue       why the line cannot be applied as is (unknown code, no quantity), null when fine
     */
    public record InvoiceLine(int row, UUID productId, String code, String designation, String lotNumber,
                              LocalDate expiryDate, Integer quantity, Long unitCostCents, Long sellingPriceCents,
                              Integer orderLineNo, String issue) {
    }

    /** @param missingColumns required columns the file lacks (by label) */
    public record InvoiceImport(String format, List<String> headers, List<String> missingColumns,
                                List<InvoiceLine> lines, int matched, int unknown, int notOrdered) {
    }

    private final ProcurementService procurement;
    private final ProductRepository products;
    private final TenantRepository tenants;
    private final TenantCalendar calendar;
    private final JdbcClient jdbc;

    public ProcurementSpreadsheets(ProcurementService procurement, ProductRepository products,
                                   TenantRepository tenants, TenantCalendar calendar, JdbcClient jdbc) {
        this.procurement = procurement;
        this.products = products;
        this.tenants = tenants;
        this.calendar = calendar;
        this.jdbc = jdbc;
    }

    /** The order as the wholesaler receives it: references, quantities, agreed prices, total. */
    @Transactional(readOnly = true)
    public byte[] orderWorkbook(UUID orderId, String pharmacyName) {
        OrderView order = procurement.order(orderId);
        int scale = currencyScale();
        List<List<Cell>> preamble = new ArrayList<>();
        preamble.add(List.of(bold("Bon de commande " + order.number())));
        preamble.add(List.of(text("Pharmacie"), text(pharmacyName)));
        preamble.add(List.of(text("Fournisseur"), text(order.supplierName())));
        preamble.add(List.of(text("Date"), text(calendar.today().format(FRENCH_DATE))));
        if (order.expectedAt() != null) {
            preamble.add(List.of(text("Livraison souhaitée"), text(order.expectedAt().format(FRENCH_DATE))));
        }
        if (order.notes() != null && !order.notes().isBlank()) {
            preamble.add(List.of(text("Notes"), text(order.notes())));
        }
        List<List<Cell>> rows = new ArrayList<>();
        long total = 0;
        int units = 0;
        for (OrderLineView line : order.lines()) {
            long lineTotal = line.unitCostCents() * line.quantityOrdered();
            total += lineTotal;
            units += line.quantityOrdered();
            rows.add(List.of(number(line.lineNo()), text(line.cip13()), text(line.designation()),
                    number(line.quantityOrdered()), amount(line.unitCostCents(), scale), amount(lineTotal, scale),
                    number(line.quantityReceived())));
        }
        rows.add(List.of(empty(), empty(), bold(order.lines().size() + " références"), number(units), empty(),
                totalAmount(total, scale), empty()));
        return XlsxWriter.write(new XlsxWriter.Sheet("Commande " + order.number(), preamble,
                List.of("N°", "Code CIP", "Désignation", "Quantité", "Prix d'achat HT", "Total HT", "Reçu"), rows,
                List.of(6, 16, 46, 10, 16, 16, 8)));
    }

    /** A booked delivery (supplier invoice): one line per lot, totals excl. VAT, VAT and incl. VAT. */
    @Transactional(readOnly = true)
    public byte[] receiptWorkbook(UUID receiptId) {
        UUID tenant = TenantContext.requireTenant().value();
        record Header(String supplier, String invoice, LocalDate invoiceDate, LocalDate receivedOn, String order,
                      long exclVat, long vat, String receivedBy) {
        }
        Header header = jdbc.sql("""
                        SELECT s.name AS supplier, g.supplier_invoice_number, g.invoice_date, g.received_at,
                               o.number AS order_number, g.total_excl_vat_cents, g.vat_cents, u.display_name
                        FROM goods_receipt g
                        JOIN supplier s ON s.id = g.supplier_id
                        LEFT JOIN purchase_order o ON o.id = g.purchase_order_id
                        LEFT JOIN app_user u ON u.id = g.received_by
                        WHERE g.tenant_id = :tenant AND g.id = :id""")
                .param("tenant", tenant).param("id", receiptId)
                .query((rs, rowNum) -> {
                    Date invoiceDate = rs.getDate("invoice_date");
                    return new Header(rs.getString("supplier"), rs.getString("supplier_invoice_number"),
                            invoiceDate == null ? null : invoiceDate.toLocalDate(),
                            rs.getTimestamp("received_at").toInstant().atZone(calendar.zone()).toLocalDate(),
                            rs.getString("order_number"), rs.getLong("total_excl_vat_cents"), rs.getLong("vat_cents"),
                            rs.getString("display_name"));
                })
                .optional()
                .orElseThrow(() -> new NotFoundException("Goods receipt", receiptId));
        int scale = currencyScale();
        List<List<Cell>> rows = jdbc.sql("""
                        SELECT p.cip13, p.designation, l.lot_number, l.expiry_date, l.quantity, l.unit_cost_cents,
                               l.vat_rate, l.selling_price_cents
                        FROM goods_receipt_line l JOIN product p ON p.id = l.product_id
                        WHERE l.tenant_id = :tenant AND l.goods_receipt_id = :id
                        ORDER BY p.designation, l.lot_number""")
                .param("tenant", tenant).param("id", receiptId)
                .query((rs, rowNum) -> {
                    long exclVat = rs.getLong("unit_cost_cents") * rs.getInt("quantity");
                    Long price = (Long) rs.getObject("selling_price_cents");
                    return List.<Cell>of(text(rs.getString("cip13")), text(rs.getString("designation")),
                            text(rs.getString("lot_number")), date(rs.getDate("expiry_date").toLocalDate()),
                            number(rs.getInt("quantity")), amount(rs.getLong("unit_cost_cents"), scale),
                            number(rs.getBigDecimal("vat_rate")), amount(exclVat, scale), amount(price, scale));
                })
                .list();
        List<List<Cell>> all = new ArrayList<>(rows);
        all.add(List.of(empty(), empty(), empty(), empty(), empty(), empty(), bold("Total HT"),
                totalAmount(header.exclVat(), scale), empty()));
        all.add(List.of(empty(), empty(), empty(), empty(), empty(), empty(), bold("TVA"),
                totalAmount(header.vat(), scale), empty()));
        all.add(List.of(empty(), empty(), empty(), empty(), empty(), empty(), bold("Total TTC"),
                totalAmount(header.exclVat() + header.vat(), scale), empty()));
        List<List<Cell>> preamble = new ArrayList<>();
        preamble.add(List.of(bold("Réception · facture " + (header.invoice() == null ? "sans numéro" : header.invoice()))));
        preamble.add(List.of(text("Fournisseur"), text(header.supplier())));
        preamble.add(List.of(text("Date de facture"), date(header.invoiceDate())));
        preamble.add(List.of(text("Reçue le"), date(header.receivedOn())));
        if (header.order() != null) {
            preamble.add(List.of(text("Commande"), text(header.order())));
        }
        preamble.add(List.of(text("Réceptionnée par"), text(header.receivedBy())));
        return XlsxWriter.write(new XlsxWriter.Sheet("Facture", preamble,
                List.of("Code CIP", "Désignation", "Lot", "Péremption", "Quantité", "Prix d'achat HT", "TVA %",
                        "Total HT", "Prix de vente"),
                all, List.of(16, 46, 14, 12, 10, 16, 8, 16, 14)));
    }

    /**
     * Reads the wholesaler's invoice or delivery note (Excel or CSV) and matches it against the
     * order: codes to products, products to order lines. Nothing is written; the receiving form
     * is pre-filled and the pharmacist checks before booking.
     */
    @Transactional(readOnly = true)
    public InvoiceImport readInvoice(UUID orderId, byte[] content, String fileName) {
        OrderView order = orderId == null ? null : procurement.order(orderId);
        TabularFile file;
        try {
            file = TabularReaders.read(content, fileName);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("unreadable-file", e.getMessage());
        }
        // A wholesaler invoice carries the columns of a stock file: code, lot, expiry, quantity, prices
        ColumnMapping mapping = ColumnMapping.detect(ImportKind.STOCK, file.headers());
        List<String> missing = mapping.missingRequired(ImportKind.STOCK);
        if (!missing.isEmpty()) {
            return new InvoiceImport(file.format(), file.headers(), missing, List.of(), 0, 0, 0);
        }
        int scale = currencyScale();
        Map<UUID, Integer> lineByProduct = new HashMap<>();
        if (order != null) {
            order.lines().forEach(line -> lineByProduct.putIfAbsent(line.productId(), line.lineNo()));
        }
        Map<String, Optional<ProductEntity>> byCode = new HashMap<>();
        // the wholesaler's own article code ("Article"), known when the supplier's references were recorded
        Integer articleColumn = articleColumn(file.headers(), mapping);
        Map<String, UUID> bySupplierReference = order == null ? Map.of() : supplierReferences(order.supplierId());
        List<InvoiceMatching.Ordered> ordered = order == null ? List.of() : order.lines().stream()
                .map(line -> new InvoiceMatching.Ordered(line.productId(), line.designation())).toList();
        Map<UUID, String> orderedNames = new HashMap<>();
        ordered.forEach(o -> orderedNames.putIfAbsent(o.productId(), o.designation()));
        List<InvoiceLine> lines = new ArrayList<>();
        int matched = 0;
        int unknown = 0;
        int notOrdered = 0;
        for (int row = 0; row < file.size(); row++) {
            String rawCode = clean(mapping.value(file, row, "code"));
            String code = ValueParsers.gtin13(rawCode).orElse(rawCode);
            String designation = clean(mapping.value(file, row, "designation"));
            if (code.isEmpty() && designation.isEmpty()) {
                continue;
            }
            Integer quantity = ValueParsers.integer(mapping.value(file, row, "quantity")).orElse(null);
            String lot = clean(mapping.value(file, row, "lot"));
            LocalDate expiry = ValueParsers.date(mapping.value(file, row, "expiry")).orElse(null);
            Long cost = ValueParsers.cents(mapping.value(file, row, "unitCost"), scale).orElse(null);
            Long price = ValueParsers.cents(mapping.value(file, row, "sellingPrice"), scale).orElse(null);
            Optional<ProductEntity> product = code.isEmpty() ? Optional.empty()
                    : byCode.computeIfAbsent(code, c -> products.findByAnyCode(c).stream().findFirst());
            UUID productId = product.map(ProductEntity::id).orElse(null);
            String name = product.map(ProductEntity::designation).orElse(designation);
            if (productId == null && articleColumn != null) {
                productId = bySupplierReference.get(clean(file.cell(row, articleColumn)).toUpperCase());
            }
            if (productId == null) {
                productId = InvoiceMatching.byDesignation(designation, ordered).orElse(null);
            }
            if (product.isEmpty() && productId != null) {
                name = orderedNames.getOrDefault(productId, designation);
            }
            String issue = null;
            Integer orderLine = null;
            if (productId == null) {
                issue = "Code inconnu du référentiel : rapprochez le produit à la main";
                unknown++;
            } else {
                orderLine = lineByProduct.get(productId);
                if (order != null && orderLine == null) {
                    issue = "Produit livré mais absent de la commande";
                    notOrdered++;
                }
            }
            if (issue == null && (quantity == null || quantity <= 0)) {
                issue = "Quantité livrée illisible";
            }
            if (issue == null) {
                matched++;
            }
            lines.add(new InvoiceLine(row + 1, productId, code, name, lot.isEmpty() ? null : lot, expiry,
                    quantity, cost, price, orderLine, issue));
        }
        return new InvoiceImport(file.format(), file.headers(), List.of(), lines, matched, unknown, notOrdered);
    }

    private static final List<String> ARTICLE_HEADERS = List.of("article", "code article", "ref article",
            "ref fournisseur", "reference fournisseur", "code fournisseur");

    /** The column of the wholesaler's article code, when the file has one not already read as another field. */
    static Integer articleColumn(List<String> headers, ColumnMapping mapping) {
        var taken = mapping.columnByField().values();
        for (int column = 0; column < headers.size(); column++) {
            if (!taken.contains(column) && ARTICLE_HEADERS.contains(ColumnMapping.normalize(headers.get(column)))) {
                return column;
            }
        }
        return null;
    }

    private Map<String, UUID> supplierReferences(UUID supplierId) {
        Map<String, UUID> references = new HashMap<>();
        jdbc.sql("""
                        SELECT supplier_reference, product_id FROM supplier_product
                        WHERE supplier_id = :supplier AND supplier_reference IS NOT NULL""")
                .param("supplier", supplierId)
                .query((ResultSet rs) -> {
                    while (rs.next()) {
                        references.putIfAbsent(rs.getString("supplier_reference").trim().toUpperCase(),
                                rs.getObject("product_id", UUID.class));
                    }
                    return null;
                });
        return references;
    }

    private static String clean(String raw) {
        String value = ValueParsers.text(raw);
        return value == null ? "" : value;
    }

    private int currencyScale() {
        return tenants.findById(TenantContext.requireTenant().value())
                .map(t -> Currency.getInstance(t.currency()).getDefaultFractionDigits())
                .orElse(0);
    }
}
