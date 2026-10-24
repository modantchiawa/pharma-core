package com.pharmacore.prescription.application;

import com.pharmacore.catalog.domain.DrugListing;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal dispensing register: every listed product sold, in ordonnancier order, with the
 * prescriber and the patient reference. Narcotics (tableau B) can be filtered on their own.
 */
@Service
public class DispensingRegisterService {

    private final JdbcClient jdbc;
    private final AuditTrail audit;

    public DispensingRegisterService(JdbcClient jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record RegisterEntry(Long ordonnancierNumber, Instant occurredAt, String ticketNumber, UUID saleId,
                                String prescriberName, String prescriberIdentifier, String patientReference,
                                String customerName, String cip13, String designation, DrugListing listing,
                                int quantity, String cashier, UUID prescriptionId) {
    }

    @Transactional(readOnly = true)
    public List<RegisterEntry> register(LocalDate from, LocalDate to, DrugListing listing, int limit) {
        ZoneId zone = zone();
        return jdbc.sql("""
                        SELECT s.ordonnancier_number, s.occurred_at, s.ticket_number, s.id AS sale_id, s.prescriber_name,
                               s.prescriber_rpps, s.patient_reference, c.display_name AS customer_name,
                               p.cip13, p.designation, p.listing, l.quantity, u.display_name AS cashier, s.prescription_id
                        FROM sale_line l
                        JOIN sale s ON s.id = l.sale_id
                        JOIN product p ON p.id = l.product_id
                        JOIN app_user u ON u.id = s.cashier_user_id
                        LEFT JOIN customer c ON c.id = s.customer_id
                        WHERE s.tenant_id = :tenant AND s.status = 'COMPLETED'
                          AND s.occurred_at >= :from AND s.occurred_at < :to
                          AND p.listing <> 'NONE'
                          AND (:listing::text IS NULL OR p.listing = :listing::text)
                        ORDER BY s.ordonnancier_number NULLS LAST, s.occurred_at, l.line_no
                        LIMIT :limit""")
                .param("tenant", TenantContext.requireTenant().value())
                .param("from", Timestamp.from(from.atStartOfDay(zone).toInstant()))
                .param("to", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()))
                .param("listing", listing == null ? null : listing.name())
                .param("limit", Math.clamp(limit, 1, 20_000))
                .query((rs, rowNum) -> new RegisterEntry(
                        rs.getObject("ordonnancier_number", Long.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("ticket_number"), rs.getObject("sale_id", UUID.class), rs.getString("prescriber_name"),
                        rs.getString("prescriber_rpps"), rs.getString("patient_reference"), rs.getString("customer_name"),
                        rs.getString("cip13"), rs.getString("designation"), DrugListing.valueOf(rs.getString("listing")),
                        rs.getInt("quantity"), rs.getString("cashier"), rs.getObject("prescription_id", UUID.class)))
                .list();
    }

    /** Export of the register for inspections; the export itself is audited. */
    @Transactional
    public String exportCsv(LocalDate from, LocalDate to, DrugListing listing) {
        List<RegisterEntry> entries = register(from, to, listing, 20_000);
        var csv = new StringBuilder("﻿Numero ordonnancier;Date;Ticket;Prescripteur;Identifiant prescripteur;"
                + "Patient;Client;CIP13;Designation;Liste;Quantite;Delivre par\n");
        for (RegisterEntry e : entries) {
            csv.append(e.ordonnancierNumber() == null ? "" : e.ordonnancierNumber()).append(';')
                    .append(e.occurredAt()).append(';').append(e.ticketNumber()).append(';')
                    .append(escape(e.prescriberName())).append(';').append(escape(e.prescriberIdentifier())).append(';')
                    .append(escape(e.patientReference())).append(';').append(escape(e.customerName())).append(';')
                    .append(e.cip13()).append(';').append(escape(e.designation())).append(';')
                    .append(e.listing()).append(';').append(e.quantity()).append(';')
                    .append(escape(e.cashier())).append('\n');
        }
        audit.record("DISPENSING_REGISTER_EXPORTED", "REGISTER", from + "_" + to, Map.of(
                "from", from.toString(), "to", to.toString(), "listing", String.valueOf(listing),
                "entries", Integer.toString(entries.size())));
        return csv.toString();
    }

    private ZoneId zone() {
        return jdbc.sql("SELECT time_zone FROM tenant WHERE id = :id")
                .param("id", TenantContext.requireTenant().value())
                .query(String.class).optional().map(ZoneId::of).orElse(ZoneId.of("UTC"));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(";") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
