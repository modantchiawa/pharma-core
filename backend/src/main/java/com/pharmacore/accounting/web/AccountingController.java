package com.pharmacore.accounting.web;

import com.pharmacore.accounting.application.AccountingReportService;
import com.pharmacore.accounting.application.AccountingReportService.BalanceRow;
import com.pharmacore.accounting.application.AccountingReportService.BalanceSheet;
import com.pharmacore.accounting.application.AccountingReportService.IncomeStatement;
import com.pharmacore.accounting.application.AccountingReportService.JournalLineView;
import com.pharmacore.accounting.domain.EntryDraft;
import com.pharmacore.accounting.domain.Journal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounting")
class AccountingController {

    private final AccountingReportService reports;

    AccountingController(AccountingReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    List<JournalLineView> journal(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) Journal journal,
                                  @RequestParam(defaultValue = "2000") int limit) {
        return reports.journal(from, to, journal, limit);
    }

    /** Export for the accountant (Excel, Sage...): one line per journal line, semicolon separated. */
    @GetMapping(value = "/journal/export", produces = "text/csv")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    ResponseEntity<byte[]> exportJournal(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var csv = new StringBuilder("﻿Numero;Date;Journal;Libelle;Compte;Intitule;Debit;Credit;Reference\n");
        for (JournalLineView line : reports.journal(from, to, null, 20_000)) {
            csv.append(line.entryNo()).append(';').append(line.date()).append(';').append(line.journal()).append(';')
                    .append(escape(line.label())).append(';').append(line.account()).append(';')
                    .append(escape(line.accountLabel())).append(';').append(line.debitCents()).append(';')
                    .append(line.creditCents()).append(';')
                    .append(line.referenceType() == null ? "" : line.referenceType() + ":" + line.referenceId())
                    .append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"journal-" + from + "-" + to + ".csv\"")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    List<BalanceRow> trialBalance(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.trialBalance(from, to);
    }

    @GetMapping("/income-statement")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    IncomeStatement incomeStatement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.incomeStatement(from, to);
    }

    @GetMapping("/balance-sheet")
    @PreAuthorize("hasAuthority('ACCOUNTING_VIEW')")
    BalanceSheet balanceSheet(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.balanceSheet(asOf);
    }

    @PostMapping("/periods/{yearMonth}/closure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    void closePeriod(@org.springframework.web.bind.annotation.PathVariable String yearMonth) {
        reports.closePeriod(YearMonth.parse(yearMonth));
    }

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNTING_MANAGE')")
    Map<String, UUID> manualEntry(@Valid @RequestBody ManualEntryRequest request) {
        var draft = EntryDraft.builder(request.journal(), request.date(), request.label());
        request.lines().forEach(line -> {
            draft.debit(line.account(), line.debitCents(), line.label());
            draft.credit(line.account(), line.creditCents(), line.label());
        });
        return Map.of("entryId", reports.postManual(draft.build()));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\"", "\"\"");
        return cleaned.contains(";") || cleaned.contains("\n") ? "\"" + cleaned + "\"" : cleaned;
    }

    record ManualEntryRequest(@NotNull Journal journal,
                              @NotNull LocalDate date,
                              @NotBlank @Size(max = 255) String label,
                              @NotEmpty @Size(max = 50) List<@Valid LineRequest> lines) {
    }

    record LineRequest(@NotBlank @Pattern(regexp = "^[1-9][0-9]{1,9}$") String account,
                       @PositiveOrZero long debitCents,
                       @PositiveOrZero long creditCents,
                       @Size(max = 255) String label) {
    }
}
