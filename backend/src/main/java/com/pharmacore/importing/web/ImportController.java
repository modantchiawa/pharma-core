package com.pharmacore.importing.web;

import com.pharmacore.importing.application.ImportOptions;
import com.pharmacore.importing.application.ImportService;
import com.pharmacore.importing.application.ImportService.JobView;
import com.pharmacore.importing.application.ImportService.Preview;
import com.pharmacore.importing.domain.ImportKind;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Data migration endpoints. A pharmacy imports its own products, stock, customers and suppliers
 * (on its hub); the editor imports the national drug reference on the cloud.
 */
@RestController
class ImportController {

    private static final TypeReference<Map<String, Integer>> MAPPING = new TypeReference<>() {
    };

    private final ImportService imports;
    private final ObjectMapper json;
    private final TenantRepository tenants;

    ImportController(ImportService imports, ObjectMapper json, TenantRepository tenants) {
        this.imports = imports;
        this.json = json;
        this.tenants = tenants;
    }

    public record KindView(ImportKind kind, String label, List<ImportService.FieldView> fields) {
    }

    @GetMapping("/api/v1/imports/kinds")
    @PreAuthorize("hasAuthority('DATA_IMPORT')")
    List<KindView> kinds() {
        return List.of(
                kind(ImportKind.PRODUCTS, "Produits et prix"),
                kind(ImportKind.STOCK, "Stock par lot"),
                kind(ImportKind.CUSTOMERS, "Clients et créances"),
                kind(ImportKind.SUPPLIERS, "Fournisseurs"));
    }

    @GetMapping(value = "/api/v1/imports/templates/{kind}", produces = "text/csv")
    @PreAuthorize("hasAuthority('DATA_IMPORT')")
    ResponseEntity<String> template(@PathVariable ImportKind kind) {
        tenantKind(kind);
        return csv(imports.template(kind), "modele-" + kind.name().toLowerCase() + ".csv");
    }

    @PostMapping(path = "/api/v1/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DATA_IMPORT')")
    Preview preview(@RequestParam("file") MultipartFile file, @RequestParam ImportKind kind,
                    @RequestParam(required = false) String mapping) throws IOException {
        return imports.preview(tenantKind(kind), file.getBytes(), file.getOriginalFilename(), mapping(mapping));
    }

    @PostMapping(path = "/api/v1/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DATA_IMPORT')")
    JobView run(@RequestParam("file") MultipartFile file, @RequestParam ImportKind kind,
                @RequestParam(required = false) String mapping,
                @RequestParam(defaultValue = "false") boolean dryRun,
                @RequestParam(defaultValue = "false") boolean updateExisting,
                @RequestParam(required = false) String defaultLot,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate defaultExpiry,
                @RequestParam(required = false) BigDecimal defaultVatRate) throws IOException {
        var options = new ImportOptions(dryRun, updateExisting, defaultLot, defaultExpiry, defaultVatRate, currencyScale());
        return imports.run(tenantKind(kind), file.getBytes(), file.getOriginalFilename(), mapping(mapping), options);
    }

    @GetMapping("/api/v1/imports")
    @PreAuthorize("hasAuthority('DATA_IMPORT')")
    List<JobView> recent(@RequestParam(defaultValue = "50") int limit) {
        return imports.recent(limit);
    }

    @GetMapping("/api/v1/imports/{id}")
    @PreAuthorize("hasAuthority('DATA_IMPORT')")
    JobView job(@PathVariable UUID id) {
        return imports.job(id);
    }

    // ------------------------------------------------------------------ platform: national reference

    @GetMapping("/api/platform/catalog/imports/kinds")
    List<KindView> catalogKinds() {
        return List.of(kind(ImportKind.CATALOG, "Référentiel national des médicaments"));
    }

    @GetMapping(value = "/api/platform/catalog/imports/template", produces = "text/csv")
    ResponseEntity<String> catalogTemplate() {
        return csv(imports.template(ImportKind.CATALOG), "modele-referentiel.csv");
    }

    @PostMapping(path = "/api/platform/catalog/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Preview catalogPreview(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String mapping)
            throws IOException {
        return imports.preview(ImportKind.CATALOG, file.getBytes(), file.getOriginalFilename(), mapping(mapping));
    }

    @PostMapping(path = "/api/platform/catalog/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    JobView catalogRun(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String mapping,
                       @RequestParam(defaultValue = "false") boolean dryRun,
                       @RequestParam(defaultValue = "true") boolean updateExisting,
                       @RequestParam(required = false) BigDecimal defaultVatRate) throws IOException {
        var options = new ImportOptions(dryRun, updateExisting, null, null, defaultVatRate, 2);
        return imports.run(ImportKind.CATALOG, file.getBytes(), file.getOriginalFilename(), mapping(mapping), options);
    }

    @GetMapping("/api/platform/catalog/imports")
    List<JobView> catalogJobs(@RequestParam(defaultValue = "50") int limit) {
        return imports.recent(limit);
    }

    // ------------------------------------------------------------------ helpers

    private static ImportKind tenantKind(ImportKind kind) {
        if (kind == ImportKind.CATALOG) {
            throw new BusinessRuleException("platform-only", "Le référentiel national s'importe depuis la supervision éditeur");
        }
        return kind;
    }

    private KindView kind(ImportKind kind, String label) {
        return new KindView(kind, label, kind.fields().stream()
                .map(f -> new ImportService.FieldView(f.name(), f.label(), f.required(), null)).toList());
    }

    private Map<String, Integer> mapping(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, MAPPING);
        } catch (tools.jackson.core.JacksonException e) {
            throw new BusinessRuleException("mapping-json", "Correspondance des colonnes illisible");
        }
    }

    private int currencyScale() {
        return tenants.findById(TenantContext.requireTenant().value())
                .map(t -> Currency.getInstance(t.currency()).getDefaultFractionDigits())
                .orElse(0);
    }

    private static ResponseEntity<String> csv(String body, String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }
}
