package com.pharmacore.importing.application;

import com.pharmacore.catalog.application.CatalogService;
import com.pharmacore.catalog.domain.DrugListing;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductEntity.ProductData;
import com.pharmacore.customers.application.CustomerService;
import com.pharmacore.customers.persistence.CustomerEntity;
import com.pharmacore.importing.domain.ColumnMapping;
import com.pharmacore.importing.domain.ImportKind;
import com.pharmacore.importing.domain.TabularFile;
import com.pharmacore.importing.domain.ValueParsers;
import com.pharmacore.inventory.application.StockService;
import com.pharmacore.inventory.application.StorageLocationService;
import com.pharmacore.procurement.application.ProcurementService;
import com.pharmacore.procurement.persistence.SupplierEntity;
import com.pharmacore.shared.context.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One loader per import kind: reads a mapped row, validates it, and writes it through the
 * regular services (so prices, lots, credit and audit follow the same rules as manual entry).
 * Each loader returns CREATED or UPDATED, or throws {@link RowRejected} with the reason shown
 * to the user.
 */
@Component
class RowLoaders {

    enum Outcome { CREATED, UPDATED }

    static final class RowRejected extends RuntimeException {
        RowRejected(String message) {
            super(message, null, false, false);
        }
    }

    private final CatalogService catalog;
    private final StockService stock;
    private final StorageLocationService locations;
    private final CustomerService customers;
    private final ProcurementService procurement;
    private final JdbcClient jdbc;
    private final CatalogWriter catalogWriter;

    RowLoaders(CatalogService catalog, StockService stock, StorageLocationService locations, CustomerService customers,
               ProcurementService procurement, JdbcClient jdbc, CatalogWriter catalogWriter) {
        this.catalog = catalog;
        this.stock = stock;
        this.locations = locations;
        this.customers = customers;
        this.procurement = procurement;
        this.jdbc = jdbc;
        this.catalogWriter = catalogWriter;
    }

    Outcome load(ImportKind kind, TabularFile file, ColumnMapping mapping, int row, ImportOptions options) {
        Row values = new Row(file, mapping, row);
        return switch (kind) {
            case CATALOG -> catalogRow(values, options);
            case PRODUCTS -> productRow(values, options);
            case STOCK -> stockRow(values, options);
            case CUSTOMERS -> customerRow(values, options);
            case SUPPLIERS -> supplierRow(values, options);
        };
    }

    // ------------------------------------------------------------------ catalog / products

    private Outcome catalogRow(Row row, ImportOptions options) {
        String code = row.code();
        boolean existed = catalog.findByCode(code).isPresent();
        if (existed && !options.updateExisting()) {
            // an unchanged national entry is left alone: counted as updated for the report
            return Outcome.UPDATED;
        }
        catalogWriter.upsert(code, productData(row, options, catalog.findByCode(code).orElse(null), "publicPrice"),
                options.dryRun());
        return existed ? Outcome.UPDATED : Outcome.CREATED;
    }

    private Outcome productRow(Row row, ImportOptions options) {
        String code = row.code();
        Optional<ProductEntity> existing = catalog.findByCode(code);
        UUID productId;
        boolean created = existing.isEmpty();
        Long sellingPrice = row.cents("sellingPrice", options).orElse(null);
        int threshold = row.integer("reorderThreshold").orElse(0);
        int reorderQuantity = row.integer("reorderQuantity").orElse(0);
        String location = row.text("location");
        if (created || options.updateExisting()) {
            productId = catalogWriter.upsert(existing.map(ProductEntity::cip13).orElse(code),
                    productData(row, options, existing.orElse(null), "sellingPrice"), options.dryRun()).id();
        } else {
            productId = existing.get().id();
        }
        if (created && options.dryRun()) {
            // the product was rolled back with the dry run: its stock settings cannot be rehearsed
            return Outcome.CREATED;
        }
        if (sellingPrice != null || threshold > 0 || reorderQuantity > 0) {
            stock.configure(productId, sellingPrice, Math.max(threshold, 0), Math.max(reorderQuantity, 0));
        }
        if (location != null) {
            locations.assignByLabel(productId, location);
        }
        return created ? Outcome.CREATED : Outcome.UPDATED;
    }

    private ProductData productData(Row row, ImportOptions options, ProductEntity existing, String priceField) {
        String designation = row.text("designation");
        if (designation == null && existing == null) {
            throw new RowRejected("Désignation manquante");
        }
        Long price = row.cents(priceField, options).orElse(existing == null ? null : existing.publicPriceCents());
        BigDecimal vat = row.percent("vatRate").orElse(existing == null ? options.defaultVatRate() : existing.vatRate());
        if (vat.signum() < 0 || vat.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new RowRejected("Taux de TVA invalide : " + vat);
        }
        Short reimbursement = row.percent("reimbursementRate").map(r -> (short) r.intValue()).orElse(null);
        if (reimbursement != null && (reimbursement < 0 || reimbursement > 100)) {
            throw new RowRejected("Taux de remboursement invalide : " + reimbursement);
        }
        boolean active = row.has("active") ? ValueParsers.truthy(row.raw("active")) : existing == null || existing.active();
        return new ProductData(
                row.ean13OrNull(),
                designation == null ? existing.designation() : designation,
                row.text("dci"),
                row.text("form"),
                row.text("dosage"),
                row.text("laboratory"),
                row.text("atc"),
                listing(row.raw("listing")),
                vat,
                price,
                reimbursement,
                null,
                active);
    }

    static DrugListing listing(String raw) {
        if (raw == null || raw.isBlank()) {
            return DrugListing.NONE;
        }
        String value = ColumnMapping.normalize(raw);
        if (value.contains("stup") || value.contains("tableau b") || value.contains("narco")) {
            return DrugListing.STUPEFIANT;
        }
        if (value.equals("ii") || value.equals("2") || value.contains("liste ii") || value.contains("liste 2")
                || value.contains("tableau c")) {
            return DrugListing.LIST_II;
        }
        if (value.equals("i") || value.equals("1") || value.contains("liste i") || value.contains("liste 1")
                || value.contains("tableau a") || value.equals("oui") || value.equals("ordonnance")) {
            return DrugListing.LIST_I;
        }
        return DrugListing.NONE;
    }

    // ------------------------------------------------------------------ stock

    private Outcome stockRow(Row row, ImportOptions options) {
        String code = row.code();
        int quantity = row.integer("quantity").orElseThrow(() -> new RowRejected("Quantité manquante ou invalide"));
        if (quantity < 0) {
            throw new RowRejected("Quantité négative");
        }
        Long sellingPrice = row.cents("sellingPrice", options).orElse(null);
        LocalDate expiry = row.date("expiry").orElse(options.defaultExpiry());
        if (quantity > 0 && expiry == null) {
            throw new RowRejected("Date de péremption manquante (indiquez une péremption par défaut pour l'import)");
        }
        String lot = Optional.ofNullable(row.text("lot")).orElse(options.defaultLot());
        long unitCost = row.cents("unitCost", options).orElse(0L);

        Optional<ProductEntity> existing = catalog.findByCode(code);
        ProductEntity product = existing.orElseGet(() -> {
            String designation = row.text("designation");
            if (designation == null) {
                throw new RowRejected("Produit " + code + " inconnu du référentiel et sans désignation pour le créer");
            }
            return catalogWriter.upsert(code, new ProductData(row.ean13OrNull(), designation, null, null, null, null,
                    null, DrugListing.NONE, options.defaultVatRate(), sellingPrice, null, null, true), options.dryRun());
        });
        if (existing.isEmpty() && options.dryRun()) {
            return Outcome.CREATED;
        }
        if (sellingPrice != null) {
            stock.configure(product.id(), sellingPrice, 0, 0);
        }
        if (quantity == 0) {
            return Outcome.UPDATED;
        }
        stock.receiveLot(product.id(), lot, expiry, quantity, unitCost);
        return Outcome.CREATED;
    }

    // ------------------------------------------------------------------ customers

    private Outcome customerRow(Row row, ImportOptions options) {
        String name = row.text("name");
        if (name == null) {
            throw new RowRejected("Nom du client manquant");
        }
        String phone = row.text("phone");
        long creditLimit = row.cents("creditLimit", options).orElse(0L);
        long balance = row.cents("balance", options).orElse(0L);
        var details = new CustomerEntity.Details(name, phone, row.text("email"), row.text("address"),
                Math.max(creditLimit, 0), row.text("notes"));
        Optional<UUID> existing = customers.findIdByPhone(phone);
        if (existing.isPresent()) {
            if (options.updateExisting()) {
                customers.update(existing.get(), details);
            }
            return Outcome.UPDATED;
        }
        var created = customers.create(details);
        if (balance > 0) {
            customers.importOpeningBalance(created.id(), balance);
        }
        return Outcome.CREATED;
    }

    // ------------------------------------------------------------------ suppliers

    private Outcome supplierRow(Row row, ImportOptions options) {
        String name = row.text("name");
        if (name == null) {
            throw new RowRejected("Nom du fournisseur manquant");
        }
        int leadTime = row.integer("leadTimeDays").orElse(1);
        var details = new SupplierEntity.Details(name, row.text("contact"), row.text("phone"), row.text("whatsapp"),
                row.text("email"), row.text("address"), Math.max(leadTime, 0), null, 0, row.text("paymentTerms"),
                row.text("notes"));
        Optional<UUID> existing = jdbc.sql("""
                        SELECT id FROM supplier WHERE tenant_id = :tenant AND lower(name) = lower(:name) LIMIT 1""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("name", name)
                .query(UUID.class).optional();
        if (existing.isPresent()) {
            if (options.updateExisting()) {
                procurement.updateSupplier(existing.get(), details);
            }
            return Outcome.UPDATED;
        }
        procurement.createSupplier(details);
        return Outcome.CREATED;
    }

    // ------------------------------------------------------------------ row access

    /** Typed access to the mapped cells of one row, with French error messages. */
    private record Row(TabularFile file, ColumnMapping mapping, int index) {

        String raw(String field) {
            return mapping.value(file, index, field);
        }

        boolean has(String field) {
            return mapping.column(field).isPresent() && !raw(field).isBlank();
        }

        String text(String field) {
            return ValueParsers.text(raw(field));
        }

        /** The product key: a valid 13-digit code, otherwise a stable internal code derived from the designation. */
        String code() {
            Optional<String> gtin = ValueParsers.gtin13(raw("code"));
            if (gtin.isPresent()) {
                return gtin.get();
            }
            String rawCode = text("code");
            String designation = text("designation");
            if (rawCode == null && designation == null) {
                throw new RowRejected("Code produit et désignation manquants");
            }
            return InternalCodes.forLabel(rawCode != null ? rawCode : designation.toUpperCase(Locale.ROOT));
        }

        String ean13OrNull() {
            Optional<String> gtin = ValueParsers.gtin13(raw("code"));
            return gtin.filter(c -> !c.startsWith("3400")).orElse(null);
        }

        Optional<Long> cents(String field, ImportOptions options) {
            String value = raw(field);
            if (value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(ValueParsers.cents(value, options.currencyScale())
                    .orElseThrow(() -> new RowRejected("Montant invalide « " + value + " »")));
        }

        Optional<Integer> integer(String field) {
            String value = raw(field);
            if (value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(ValueParsers.integer(value)
                    .orElseThrow(() -> new RowRejected("Nombre invalide « " + value + " »")));
        }

        Optional<BigDecimal> percent(String field) {
            String value = raw(field);
            if (value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(ValueParsers.percent(value)
                    .orElseThrow(() -> new RowRejected("Pourcentage invalide « " + value + " »")));
        }

        Optional<LocalDate> date(String field) {
            String value = raw(field);
            if (value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(ValueParsers.date(value)
                    .orElseThrow(() -> new RowRejected("Date invalide « " + value + " »")));
        }
    }
}
