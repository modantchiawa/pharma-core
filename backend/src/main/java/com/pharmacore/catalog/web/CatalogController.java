package com.pharmacore.catalog.web;

import com.pharmacore.catalog.application.CatalogService;
import com.pharmacore.catalog.application.CatalogService.PosCatalogPage;
import com.pharmacore.catalog.domain.DrugListing;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductEntity.ProductData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogController {

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /** National catalog maintenance (Super-Admin). */
    @PutMapping("/api/platform/catalog/products/{cip13}")
    ProductView upsert(@PathVariable @Pattern(regexp = "^[0-9]{13}$") String cip13,
                       @Valid @RequestBody ProductRequest request) {
        return ProductView.of(catalog.upsert(cip13, request.toData()));
    }

    @GetMapping("/api/v1/catalog/products")
    List<ProductView> search(@RequestParam @Size(min = 2, max = 100) String q,
                             @RequestParam(defaultValue = "20") int limit) {
        return catalog.search(q, limit).stream().map(ProductView::of).toList();
    }

    /** Substitutes with the same active substance, with this pharmacy's price and stock. */
    @GetMapping("/api/v1/catalog/products/{productId}/alternatives")
    List<CatalogService.Alternative> alternatives(@PathVariable UUID productId) {
        return catalog.alternatives(productId);
    }

    /** Incremental feed consumed by the offline POS (IndexedDB cache). */
    @GetMapping("/api/v1/pos/catalog")
    PosCatalogPage posCatalog(@RequestParam(defaultValue = "1970-01-01T00:00:00Z")
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                              @RequestParam(required = false) UUID after,
                              @RequestParam(defaultValue = "500") int limit) {
        return catalog.posChanges(since, after, limit);
    }

    record ProductRequest(@Pattern(regexp = "^[0-9]{13}$") String ean13,
                          @NotBlank @Size(max = 255) String designation,
                          @Size(max = 255) String dci,
                          @Size(max = 100) String pharmaceuticalForm,
                          @Size(max = 100) String dosage,
                          @Size(max = 150) String laboratory,
                          @Size(max = 10) String atcCode,
                          @NotNull DrugListing listing,
                          @NotNull @DecimalMin("0") BigDecimal vatRate,
                          @PositiveOrZero Long publicPriceCents,
                          @Min(0) @Max(100) Short reimbursementRate,
                          @Size(max = 500) String monographUrl,
                          boolean active) {

        ProductData toData() {
            return new ProductData(ean13, designation, dci, pharmaceuticalForm, dosage, laboratory, atcCode, listing,
                    vatRate, publicPriceCents, reimbursementRate, monographUrl, active);
        }
    }

    record ProductView(UUID id, String cip13, ProductData data, Instant updatedAt) {

        static ProductView of(ProductEntity product) {
            return new ProductView(product.id(), product.cip13(), product.data(), product.updatedAt());
        }
    }
}
