package com.pharmacore.printing.application;

import com.pharmacore.printing.application.PrinterSettings.InvoicePaper;
import com.pharmacore.printing.application.PrinterSettings.Mode;
import com.pharmacore.printing.application.PrinterSettings.Paper;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Printer configuration per cash register, replicated to the cloud like any pharmacy setting. */
@Service
public class PrintSettingsService {

    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;

    public PrintSettingsService(JdbcClient jdbc, AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PrinterSettings forTerminal(String terminalId) {
        return jdbc.sql("""
                        SELECT terminal_id, mode, paper, address, width_chars, copies, cut, drawer_kick, auto_print, invoice_paper
                        FROM terminal_printer WHERE tenant_id = :tenant AND terminal_id = :terminal""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("terminal", terminalId)
                .query((rs, rowNum) -> map(rs.getString("terminal_id"), rs.getString("mode"), rs.getString("paper"),
                        rs.getString("address"), rs.getInt("width_chars"), rs.getInt("copies"), rs.getBoolean("cut"),
                        rs.getBoolean("drawer_kick"), rs.getBoolean("auto_print"), rs.getString("invoice_paper")))
                .optional()
                .orElseGet(() -> PrinterSettings.defaults(terminalId));
    }

    /** Every register the pharmacy knows: configured ones and those seen through heartbeats. */
    @Transactional(readOnly = true)
    public List<PrinterSettings> all() {
        UUID tenant = TenantContext.requireTenant().value();
        return jdbc.sql("""
                        SELECT t.terminal_id, p.mode, p.paper, p.address, p.width_chars, p.copies, p.cut, p.drawer_kick,
                               p.auto_print, p.invoice_paper
                        FROM (SELECT terminal_id FROM terminal_printer WHERE tenant_id = :tenant
                              UNION SELECT terminal_id FROM terminal_heartbeat WHERE tenant_id = :tenant) t
                        LEFT JOIN terminal_printer p ON p.tenant_id = :tenant AND p.terminal_id = t.terminal_id
                        ORDER BY t.terminal_id""")
                .param("tenant", tenant)
                .query((rs, rowNum) -> rs.getString("mode") == null
                        ? PrinterSettings.defaults(rs.getString("terminal_id"))
                        : map(rs.getString("terminal_id"), rs.getString("mode"), rs.getString("paper"),
                                rs.getString("address"), rs.getInt("width_chars"), rs.getInt("copies"),
                                rs.getBoolean("cut"), rs.getBoolean("drawer_kick"), rs.getBoolean("auto_print"),
                                rs.getString("invoice_paper")))
                .list();
    }

    @Transactional
    public PrinterSettings save(PrinterSettings settings) {
        var actor = TenantContext.requireTenantUser();
        jdbc.sql("""
                        INSERT INTO terminal_printer (id, tenant_id, terminal_id, mode, paper, address, width_chars, copies,
                                                      cut, drawer_kick, auto_print, invoice_paper, updated_by, updated_at)
                        VALUES (:id, :tenant, :terminal, :mode, :paper, :address, :width, :copies, :cut, :drawer, :auto,
                                :invoice, :by, :at)
                        ON CONFLICT (tenant_id, terminal_id) DO UPDATE SET
                            mode = EXCLUDED.mode, paper = EXCLUDED.paper, address = EXCLUDED.address,
                            width_chars = EXCLUDED.width_chars, copies = EXCLUDED.copies, cut = EXCLUDED.cut,
                            drawer_kick = EXCLUDED.drawer_kick, auto_print = EXCLUDED.auto_print,
                            invoice_paper = EXCLUDED.invoice_paper, updated_by = EXCLUDED.updated_by,
                            updated_at = EXCLUDED.updated_at, version = terminal_printer.version + 1""")
                .param("id", UUID.randomUUID())
                .param("tenant", actor.tenant().value())
                .param("terminal", settings.terminalId())
                .param("mode", settings.mode().name())
                .param("paper", settings.paper().name())
                .param("address", settings.address() == null ? null : settings.address().strip())
                .param("width", settings.widthChars())
                .param("copies", settings.copies())
                .param("cut", settings.cut())
                .param("drawer", settings.drawerKick())
                .param("auto", settings.autoPrint())
                .param("invoice", settings.invoicePaper().name())
                .param("by", actor.userId())
                .param("at", Timestamp.from(clock.instant()))
                .update();
        audit.record("PRINTER_CONFIGURED", "TERMINAL", settings.terminalId(), Map.of(
                "mode", settings.mode().name(), "paper", settings.paper().name(),
                "address", String.valueOf(settings.address())));
        return forTerminal(settings.terminalId());
    }

    private static PrinterSettings map(String terminalId, String mode, String paper, String address, int width,
                                       int copies, boolean cut, boolean drawerKick, boolean autoPrint,
                                       String invoicePaper) {
        return new PrinterSettings(terminalId, Mode.valueOf(mode), Paper.valueOf(paper), address, width, copies, cut,
                drawerKick, autoPrint, InvoicePaper.valueOf(invoicePaper), true);
    }
}
