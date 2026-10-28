package com.pharmacore.safety.web;

import com.pharmacore.importing.domain.CsvReader;
import com.pharmacore.importing.domain.TabularFile;
import com.pharmacore.safety.application.SafetyRuleStore;
import com.pharmacore.safety.domain.SafetyRule;
import com.pharmacore.safety.domain.Severity;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.web.BusinessRuleException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Maintenance of the interaction referential by the platform operator: JSON upsert, CSV import
 * (columns {@code kind;substance;counterpart;severity;advice}) and deactivation.
 */
@RestController
@RequestMapping("/api/platform/safety/rules")
class PlatformSafetyController {

    private static final int MAX_ROWS = 20_000;

    private final SafetyRuleStore rules;
    private final AuditTrail audit;

    PlatformSafetyController(SafetyRuleStore rules, AuditTrail audit) {
        this.rules = rules;
        this.audit = audit;
    }

    record RuleRequest(@NotNull SafetyRule.Kind kind,
                       @NotBlank @Size(max = 120) String substance,
                       @NotBlank @Size(max = 120) String counterpart,
                       @NotNull Severity severity,
                       @NotBlank @Size(max = 400) String advice) {

        SafetyRule toRule() {
            try {
                return new SafetyRule(kind, substance, counterpart, severity, advice);
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("invalid-rule", e.getMessage());
            }
        }
    }

    record BatchRequest(@NotEmpty @Size(max = MAX_ROWS) List<@Valid RuleRequest> rules, @Size(max = 120) String source) {
    }

    record Outcome(int written, int rejected, long activeRules, List<String> errors) {
    }

    @GetMapping("/count")
    long count() {
        return rules.countActive();
    }

    @PutMapping
    Outcome upsert(@Valid @RequestBody BatchRequest request) {
        List<SafetyRule> parsed = request.rules().stream().map(RuleRequest::toRule).toList();
        int written = rules.upsert(parsed, source(request.source()));
        audit.record("SAFETY_RULES_UPSERTED", "DRUG_INTERACTION", "batch", Map.of("rows", Integer.toString(written)));
        return new Outcome(written, 0, rules.countActive(), List.of());
    }

    @PostMapping("/import")
    Outcome importCsv(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String source)
            throws IOException {
        TabularFile table = CsvReader.read(file.getBytes());
        int kind = column(table, "kind", "type");
        int substance = column(table, "substance", "dci");
        int counterpart = column(table, "counterpart", "contrepartie", "contexte");
        int severity = column(table, "severity", "gravite", "severite");
        int advice = column(table, "advice", "conduite", "conseil");
        if (kind < 0 || substance < 0 || counterpart < 0 || severity < 0 || advice < 0) {
            throw new BusinessRuleException("missing-columns",
                    "Colonnes attendues : kind, substance, counterpart, severity, advice");
        }
        List<SafetyRule> parsed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int row = 0; row < Math.min(table.size(), MAX_ROWS); row++) {
            try {
                parsed.add(new SafetyRule(SafetyRule.Kind.valueOf(table.cell(row, kind).trim().toUpperCase(Locale.ROOT)),
                        table.cell(row, substance), table.cell(row, counterpart),
                        Severity.valueOf(table.cell(row, severity).trim().toUpperCase(Locale.ROOT)),
                        table.cell(row, advice)));
            } catch (IllegalArgumentException e) {
                errors.add("Ligne " + (row + 2) + " : " + e.getMessage());
            }
        }
        int written = rules.upsert(parsed, source(source));
        audit.record("SAFETY_RULES_IMPORTED", "DRUG_INTERACTION", "csv", Map.of(
                "rows", Integer.toString(written), "rejected", Integer.toString(errors.size())));
        return new Outcome(written, errors.size(), rules.countActive(), errors.subList(0, Math.min(errors.size(), 50)));
    }

    @DeleteMapping
    Outcome deactivate(@RequestParam SafetyRule.Kind kind, @RequestParam String substance,
                       @RequestParam String counterpart) {
        boolean done = rules.deactivate(kind, substance, counterpart);
        if (done) {
            audit.record("SAFETY_RULE_DEACTIVATED", "DRUG_INTERACTION", substance + "|" + counterpart,
                    Map.of("kind", kind.name()));
        }
        return new Outcome(done ? 1 : 0, 0, rules.countActive(), List.of());
    }

    private static String source(String value) {
        return value == null || value.isBlank() ? "Éditeur" : value.trim();
    }

    private static int column(TabularFile table, String... names) {
        for (int i = 0; i < table.headers().size(); i++) {
            String header = table.headers().get(i).trim().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (header.equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
