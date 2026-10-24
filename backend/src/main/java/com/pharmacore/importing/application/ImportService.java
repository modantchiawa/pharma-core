package com.pharmacore.importing.application;

import com.pharmacore.importing.domain.ColumnMapping;
import com.pharmacore.importing.domain.ImportKind;
import com.pharmacore.importing.domain.ImportReport;
import com.pharmacore.importing.domain.TabularFile;
import com.pharmacore.importing.domain.TabularReaders;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Migration of data from another program or from the editor's drug reference. A file is first
 * previewed (format, detected columns, first rows parsed) then run: every row is loaded in its
 * own transaction, so one bad line never cancels the thousands of good ones, and the outcome is
 * kept in {@code import_job} for traceability. A dry run executes the same code and rolls each row
 * back.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    static final int PREVIEW_ROWS = 20;
    static final int MAX_ROWS = 100_000;

    private final RowLoaders loaders;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final Clock clock;

    public ImportService(RowLoaders loaders, PlatformTransactionManager transactionManager, JdbcClient jdbc,
                         ObjectMapper json, AuditTrail audit, Clock clock) {
        this.loaders = loaders;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    public record FieldView(String name, String label, boolean required, Integer column) {
    }

    public record PreviewRow(int line, Map<String, String> values, String error) {
    }

    public record Preview(String format, String charset, String delimiter, List<String> headers, int rows,
                          List<FieldView> fields, List<String> missingRequired, List<PreviewRow> sample) {
    }

    public record JobView(UUID id, ImportKind kind, String fileName, String fileFormat, boolean dryRun, String status,
                          int total, int created, int updated, int rejected, List<ImportReport.RowError> errors,
                          String startedBy, Instant startedAt, Instant finishedAt) {
    }

    /** Reads the file and proposes a column mapping; a user mapping, when given, overrides the detection. */
    public Preview preview(ImportKind kind, byte[] content, String fileName, Map<String, Integer> userMapping) {
        TabularFile file = read(content, fileName);
        ColumnMapping mapping = mapping(kind, file, userMapping);
        List<PreviewRow> sample = new ArrayList<>();
        for (int row = 0; row < Math.min(PREVIEW_ROWS, file.size()); row++) {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            for (ImportKind.Field field : kind.fields()) {
                values.put(field.name(), mapping.value(file, row, field.name()));
            }
            sample.add(new PreviewRow(row + 2, values, validateOnly(kind, file, mapping, row)));
        }
        return new Preview(file.format(), file.charset(), file.delimiter(), file.headers(), file.size(),
                fields(kind, mapping), mapping.missingRequired(kind), sample);
    }

    /** Loads the file row by row and records the job. */
    public JobView run(ImportKind kind, byte[] content, String fileName, Map<String, Integer> userMapping,
                       ImportOptions options) {
        TabularFile file = read(content, fileName);
        ColumnMapping mapping = mapping(kind, file, userMapping);
        List<String> missing = mapping.missingRequired(kind);
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("mapping-incomplete", "Colonnes obligatoires non reconnues : " + String.join(", ", missing));
        }
        if (file.size() > MAX_ROWS) {
            throw new BusinessRuleException("file-too-large", "Le fichier dépasse " + MAX_ROWS + " lignes : découpez-le");
        }
        Instant startedAt = clock.instant();
        ImportReport report = new ImportReport();
        for (int row = 0; row < file.size(); row++) {
            int line = row + 2;
            int current = row;
            try {
                RowLoaders.Outcome outcome = tx.execute(status -> {
                    RowLoaders.Outcome result = loaders.load(kind, file, mapping, current, options);
                    if (options.dryRun()) {
                        status.setRollbackOnly();
                    }
                    return result;
                });
                if (outcome == RowLoaders.Outcome.CREATED) {
                    report.created();
                } else {
                    report.updated();
                }
            } catch (RowLoaders.RowRejected | BusinessRuleException e) {
                report.rejected(line, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Import {} ligne {} rejetée", kind, line, e);
                report.rejected(line, "Ligne refusée par la base : " + rootMessage(e));
            }
        }
        return record(kind, fileName, file.format(), options.dryRun(), mapping, report, startedAt);
    }

    public List<JobView> recent(int limit) {
        return jdbc.sql("""
                        SELECT * FROM import_job WHERE tenant_id = :tenant ORDER BY started_at DESC LIMIT :limit""")
                .param("tenant", currentTenant())
                .param("limit", Math.clamp(limit, 1, 200))
                .query((rs, rowNum) -> toView(rs))
                .list();
    }

    public JobView job(UUID id) {
        return jdbc.sql("SELECT * FROM import_job WHERE id = :id AND tenant_id = :tenant")
                .param("id", id).param("tenant", currentTenant())
                .query((rs, rowNum) -> toView(rs))
                .optional()
                .orElseThrow(() -> new NotFoundException("Import job", id));
    }

    /** CSV skeleton with the expected headers and one example line, to fill in a spreadsheet. */
    public String template(ImportKind kind) {
        String header = String.join(";", kind.fields().stream().map(ImportKind.Field::label).toList());
        String example = switch (kind) {
            case CATALOG -> "3400930000000;PARACETAMOL 500MG CPR B/16;PARACETAMOL;Comprimé;500 mg;Laborex;N02BE01;;0;1250;;oui";
            case PRODUCTS -> "3400930000000;PARACETAMOL 500MG CPR B/16;PARACETAMOL;Comprimé;500 mg;Laborex;;0;1250;20;60;Rayon A";
            case STOCK -> "3400930000000;PARACETAMOL 500MG CPR B/16;LOT2026A;09/2027;48;800;1250";
            case CUSTOMERS -> "Mme Hadiza Moussa;+22790000000;;Quartier Plateau;50000;12500;Bonne cliente";
            case SUPPLIERS -> "Laborex Niger;M. Issoufou;+22720000000;+22790000001;commandes@laborex.ne;Zone industrielle, Niamey;1;30 jours fin de mois;";
        };
        return "﻿" + header + "\n" + example + "\n";
    }

    // ------------------------------------------------------------------ helpers

    private TabularFile read(byte[] content, String fileName) {
        try {
            TabularFile file = TabularReaders.read(content, fileName);
            if (file.headers().isEmpty()) {
                throw new BusinessRuleException("empty-file", "Le fichier ne contient aucune ligne d'en-tête");
            }
            return file;
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("unreadable-file", e.getMessage());
        }
    }

    private static ColumnMapping mapping(ImportKind kind, TabularFile file, Map<String, Integer> userMapping) {
        ColumnMapping detected = ColumnMapping.detect(kind, file.headers());
        if (userMapping == null || userMapping.isEmpty()) {
            return detected;
        }
        Map<String, Integer> merged = new java.util.LinkedHashMap<>(detected.columnByField());
        userMapping.forEach((field, column) -> {
            kind.field(field);
            if (column == null || column < 0) {
                merged.remove(field);
            } else if (column >= file.headers().size()) {
                throw new BusinessRuleException("mapping-column", "Colonne " + column + " inexistante pour " + field);
            } else {
                merged.put(field, column);
            }
        });
        return new ColumnMapping(merged);
    }

    private static List<FieldView> fields(ImportKind kind, ColumnMapping mapping) {
        return kind.fields().stream()
                .map(f -> new FieldView(f.name(), f.label(), f.required(), mapping.column(f.name()).orElse(null)))
                .toList();
    }

    /** Runs the loader and rolls back: the preview shows the error a row would raise. */
    private String validateOnly(ImportKind kind, TabularFile file, ColumnMapping mapping, int row) {
        if (!mapping.missingRequired(kind).isEmpty()) {
            return null;
        }
        try {
            tx.execute(status -> {
                loaders.load(kind, file, mapping, row, ImportOptions.defaults(0));
                status.setRollbackOnly();
                return null;
            });
            return null;
        } catch (RowLoaders.RowRejected | BusinessRuleException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return "Ligne refusée par la base : " + rootMessage(e);
        }
    }

    private JobView record(ImportKind kind, String fileName, String format, boolean dryRun, ColumnMapping mapping,
                           ImportReport report, Instant startedAt) {
        UUID id = UUID.randomUUID();
        Instant finishedAt = clock.instant();
        String by = TenantContext.actor().auditId();
        tx.executeWithoutResult(status -> jdbc.sql("""
                        INSERT INTO import_job (id, tenant_id, kind, file_name, file_format, dry_run, status, total_rows,
                                                created_rows, updated_rows, rejected_rows, errors, mapping, started_by,
                                                started_at, finished_at)
                        VALUES (:id, :tenant, :kind, :file, :format, :dry, 'DONE', :total, :created, :updated, :rejected,
                                :errors::jsonb, :mapping::jsonb, :by, :started, :finished)""")
                .param("id", id).param("tenant", currentTenant()).param("kind", kind.name())
                .param("file", fileName == null || fileName.isBlank() ? "import" : fileName.substring(0, Math.min(255, fileName.length())))
                .param("format", format).param("dry", dryRun)
                .param("total", report.total()).param("created", report.createdCount())
                .param("updated", report.updatedCount()).param("rejected", report.rejectedCount())
                .param("errors", write(report.errors())).param("mapping", write(mapping.columnByField()))
                .param("by", by).param("started", Timestamp.from(startedAt)).param("finished", Timestamp.from(finishedAt))
                .update());
        tx.executeWithoutResult(status -> audit.record("DATA_IMPORTED", "IMPORT_JOB", id, Map.of(
                "kind", kind.name(), "file", fileName == null ? "" : fileName, "dryRun", Boolean.toString(dryRun),
                "created", Integer.toString(report.createdCount()), "updated", Integer.toString(report.updatedCount()),
                "rejected", Integer.toString(report.rejectedCount()))));
        return new JobView(id, kind, fileName, format, dryRun, "DONE", report.total(), report.createdCount(),
                report.updatedCount(), report.rejectedCount(), report.errors(), by, startedAt, finishedAt);
    }

    private JobView toView(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<ImportReport.RowError> errors;
        try {
            errors = json.readerForListOf(ImportReport.RowError.class).readValue(rs.getString("errors"));
        } catch (JacksonException e) {
            errors = List.of();
        }
        return new JobView(rs.getObject("id", UUID.class), ImportKind.valueOf(rs.getString("kind")),
                rs.getString("file_name"), rs.getString("file_format"), rs.getBoolean("dry_run"), rs.getString("status"),
                rs.getInt("total_rows"), rs.getInt("created_rows"), rs.getInt("updated_rows"), rs.getInt("rejected_rows"),
                errors, rs.getString("started_by"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at").toInstant());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID currentTenant() {
        return TenantContext.actor().tenantId().orElse(TenantId.PLATFORM).value();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
