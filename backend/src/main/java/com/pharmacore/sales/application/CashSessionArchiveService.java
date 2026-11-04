package com.pharmacore.sales.application;

import static com.pharmacore.importing.domain.XlsxWriter.amount;
import static com.pharmacore.importing.domain.XlsxWriter.bold;
import static com.pharmacore.importing.domain.XlsxWriter.number;
import static com.pharmacore.importing.domain.XlsxWriter.text;
import static com.pharmacore.importing.domain.XlsxWriter.totalAmount;

import com.pharmacore.importing.domain.XlsxWriter;
import com.pharmacore.importing.domain.XlsxWriter.Cell;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.NotFoundException;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Archive of a cash session: at the closing, every operation of the session (each sale with its
 * payments, cancellations, partial returns, drawer movements, debt collections) is frozen in one
 * document with its SHA-256 fingerprint. The Z report gives the totals; the archive keeps the
 * detail behind them, unchanged, for the owner, the accountant or a control.
 */
@Service
public class CashSessionArchiveService {

    public record ArchivedPayment(String method, String provider, String reference, long amountCents,
                                  String confirmationStatus) {
    }

    public record ArchivedSale(UUID saleId, String ticketNumber, Instant occurredAt, String cashier, String origin,
                               String status, long grossCents, long discountCents, long netCents, String anomalies,
                               Instant cancelledAt, String cancelledBy, String cancelReason,
                               List<ArchivedPayment> payments) {
    }

    public record ArchivedReturn(UUID returnId, String ticketNumber, Instant occurredAt, String recordedBy,
                                 long refundCents, String reason) {
    }

    public record ArchivedMovement(String kind, long amountCents, String reason, String expenseAccount,
                                   String recordedBy, Instant occurredAt) {
    }

    public record ArchivedCollection(String customer, String method, String provider, String reference,
                                     long amountCents, String receivedBy, Instant receivedAt) {
    }

    public record SessionArchive(UUID sessionId, String terminalId, String openedBy, Instant openedAt,
                                 String closedBy, Instant closedAt, long openingFloatCents, Long expectedCashCents,
                                 Long countedCashCents, Long differenceCents, String notes, String reportHash,
                                 int salesCount, int cancelledCount, long salesNetCents, long refundsCents,
                                 List<ArchivedSale> sales, List<ArchivedReturn> returns,
                                 List<ArchivedMovement> movements, List<ArchivedCollection> collections) {
    }

    /** @param intact the stored document still matches its fingerprint */
    public record ArchiveView(SessionArchive archive, String contentHash, Instant archivedAt, boolean intact) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TenantCalendar calendar;
    private final TenantRepository tenants;
    private final Clock clock;

    public CashSessionArchiveService(JdbcClient jdbc, ObjectMapper json, TenantCalendar calendar,
                                     TenantRepository tenants, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.calendar = calendar;
        this.tenants = tenants;
        this.clock = clock;
    }

    /** Freezes the session's operations; called by the closing, in its transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String capture(UUID sessionId) {
        SessionArchive archive = build(sessionId);
        String content = json.writeValueAsString(archive);
        String hash = sha256(content);
        jdbc.sql("""
                        INSERT INTO cash_session_archive (session_id, tenant_id, content, content_hash, created_at)
                        VALUES (:session, :tenant, :content::jsonb, :hash, :at)
                        ON CONFLICT (session_id) DO NOTHING""")
                .param("session", sessionId)
                .param("tenant", TenantContext.requireTenant().value())
                .param("content", content)
                .param("hash", hash)
                .param("at", Timestamp.from(clock.instant()))
                .update();
        return hash;
    }

    @Transactional(readOnly = true)
    public ArchiveView get(UUID sessionId) {
        record Stored(String content, String hash, Instant at) {
        }
        Stored stored = jdbc.sql("""
                        SELECT content::text AS content, content_hash, created_at
                        FROM cash_session_archive WHERE session_id = :session""")
                .param("session", sessionId)
                .query((rs, rowNum) -> new Stored(rs.getString("content"), rs.getString("content_hash"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional()
                .orElseThrow(() -> new NotFoundException("Cash session archive", sessionId));
        SessionArchive archive = json.readValue(stored.content(), SessionArchive.class);
        // jsonb normalises the layout: the fingerprint is checked on the document written again
        boolean intact = sha256(json.writeValueAsString(archive)).equals(stored.hash());
        return new ArchiveView(archive, stored.hash(), stored.at(), intact);
    }

    /** The archive as a workbook: summary, sales, payments, returns, movements, debt collections. */
    @Transactional(readOnly = true)
    public byte[] workbook(UUID sessionId) {
        ArchiveView view = get(sessionId);
        SessionArchive a = view.archive();
        int scale = tenants.findById(TenantContext.requireTenant().value())
                .map(t -> Currency.getInstance(t.currency()).getDefaultFractionDigits()).orElse(0);
        ZoneId zone = calendar.zone();
        DateTimeFormatter at = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone);

        List<List<Cell>> summary = new ArrayList<>();
        summary.add(List.of(text("Poste"), text(a.terminalId())));
        summary.add(List.of(text("Ouverte par"), text(a.openedBy())));
        summary.add(List.of(text("Ouverte le"), text(at.format(a.openedAt()))));
        summary.add(List.of(text("Clôturée par"), text(a.closedBy())));
        summary.add(List.of(text("Clôturée le"), text(a.closedAt() == null ? "" : at.format(a.closedAt()))));
        summary.add(List.of(text("Fond de caisse"), amount(a.openingFloatCents(), scale)));
        summary.add(List.of(text("Ventes"), number(a.salesCount())));
        summary.add(List.of(text("Annulées"), number(a.cancelledCount())));
        summary.add(List.of(text("Ventes nettes"), amount(a.salesNetCents(), scale)));
        summary.add(List.of(text("Remboursements (retours)"), amount(a.refundsCents(), scale)));
        summary.add(List.of(text("Espèces attendues"), amount(a.expectedCashCents(), scale)));
        summary.add(List.of(text("Espèces comptées"), amount(a.countedCashCents(), scale)));
        summary.add(List.of(text("Écart"), amount(a.differenceCents(), scale)));
        summary.add(List.of(text("Notes"), text(a.notes())));
        summary.add(List.of(text("Empreinte du rapport Z"), text(a.reportHash())));
        summary.add(List.of(text("Empreinte de l'archive"), text(view.contentHash())));
        summary.add(List.of(text("Archive intacte"), text(view.intact() ? "Oui" : "NON : contenu modifié")));

        List<List<Cell>> sales = new ArrayList<>();
        List<List<Cell>> payments = new ArrayList<>();
        for (ArchivedSale sale : a.sales()) {
            sales.add(List.of(text(sale.ticketNumber()), text(at.format(sale.occurredAt())), text(sale.cashier()),
                    text("CANCELLED".equals(sale.status()) ? "Annulée" : "Validée"),
                    text("OFFLINE_REPLAY".equals(sale.origin()) ? "Hors ligne" : "En ligne"),
                    amount(sale.grossCents(), scale), amount(sale.discountCents(), scale), amount(sale.netCents(), scale),
                    text(sale.anomalies()), text(sale.cancelReason()), text(sale.cancelledBy())));
            for (ArchivedPayment payment : sale.payments()) {
                payments.add(List.of(text(sale.ticketNumber()), text(payment.method()), text(payment.provider()),
                        text(payment.reference()), amount(payment.amountCents(), scale),
                        text(payment.confirmationStatus())));
            }
        }
        sales.add(List.of(bold("Total"), text(""), text(""), text(""), text(""), text(""), text(""),
                totalAmount(a.salesNetCents(), scale)));
        List<List<Cell>> returns = a.returns().stream().map(r -> List.<Cell>of(text(r.ticketNumber()),
                text(at.format(r.occurredAt())), text(r.recordedBy()), amount(r.refundCents(), scale),
                text(r.reason()))).toList();
        List<List<Cell>> movements = a.movements().stream().map(m -> List.<Cell>of(text(m.kind()),
                amount(m.amountCents(), scale), text(m.reason()), text(m.expenseAccount()), text(m.recordedBy()),
                text(at.format(m.occurredAt())))).toList();
        List<List<Cell>> collections = a.collections().stream().map(c -> List.<Cell>of(text(c.customer()),
                text(c.method()), text(c.provider()), text(c.reference()), amount(c.amountCents(), scale),
                text(c.receivedBy()), text(at.format(c.receivedAt())))).toList();

        return XlsxWriter.write(
                new XlsxWriter.Sheet("Résumé", List.of(List.of(bold("Archive de la session " + a.terminalId()))),
                        List.of("Rubrique", "Valeur"), summary, List.of(28, 70)),
                new XlsxWriter.Sheet("Ventes", List.of(), List.of("Ticket", "Date", "Vendeur", "Statut", "Origine",
                        "Brut", "Remise", "Net", "Anomalies", "Motif d'annulation", "Annulée par"), sales,
                        List.of(18, 17, 22, 10, 11, 12, 12, 12, 26, 30, 20)),
                new XlsxWriter.Sheet("Règlements", List.of(), List.of("Ticket", "Moyen", "Opérateur", "Référence",
                        "Montant", "Confirmation"), payments, List.of(18, 18, 16, 22, 12, 14)),
                new XlsxWriter.Sheet("Retours", List.of(), List.of("Ticket", "Date", "Par", "Remboursé", "Motif"),
                        returns, List.of(18, 17, 22, 12, 40)),
                new XlsxWriter.Sheet("Mouvements", List.of(), List.of("Type", "Montant", "Motif", "Compte", "Par",
                        "Date"), movements, List.of(12, 12, 40, 10, 22, 17)),
                new XlsxWriter.Sheet("Créances encaissées", List.of(), List.of("Client", "Moyen", "Opérateur",
                        "Référence", "Montant", "Reçu par", "Date"), collections, List.of(26, 14, 16, 22, 12, 22, 17)));
    }

    // ------------------------------------------------------------------ building

    private SessionArchive build(UUID sessionId) {
        record Head(String terminal, String openedBy, Instant openedAt, String closedBy, Instant closedAt,
                    long floatCents, Long expected, Long counted, Long difference, String notes, String hash) {
        }
        Head head = jdbc.sql("""
                        SELECT cs.terminal_id, uo.display_name AS opened_by, cs.opened_at, uc.display_name AS closed_by,
                               cs.closed_at, cs.opening_float_cents, cs.expected_cash_cents, cs.counted_cash_cents,
                               cs.difference_cents, cs.notes, cs.report_hash
                        FROM cash_session cs
                        LEFT JOIN app_user uo ON uo.id = cs.opened_by
                        LEFT JOIN app_user uc ON uc.id = cs.closed_by
                        WHERE cs.id = :session""")
                .param("session", sessionId)
                .query((rs, rowNum) -> new Head(rs.getString("terminal_id"), rs.getString("opened_by"),
                        instant(rs, "opened_at"), rs.getString("closed_by"), instant(rs, "closed_at"),
                        rs.getLong("opening_float_cents"), (Long) rs.getObject("expected_cash_cents"),
                        (Long) rs.getObject("counted_cash_cents"), (Long) rs.getObject("difference_cents"),
                        rs.getString("notes"), rs.getString("report_hash")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Cash session", sessionId));

        Map<UUID, List<ArchivedPayment>> paymentsBySale = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT p.sale_id, p.method, p.provider, p.reference, p.amount_cents, p.confirmation_status
                        FROM sale_payment p JOIN sale s ON s.id = p.sale_id
                        WHERE s.cash_session_id = :session
                        ORDER BY p.sale_id, p.id""")
                .param("session", sessionId)
                .query((RowCallbackHandler) rs -> paymentsBySale
                        .computeIfAbsent(rs.getObject("sale_id", UUID.class), id -> new ArrayList<>())
                        .add(new ArchivedPayment(rs.getString("method"), rs.getString("provider"),
                                rs.getString("reference"), rs.getLong("amount_cents"),
                                rs.getString("confirmation_status"))));
        List<ArchivedSale> sales = jdbc.sql("""
                        SELECT s.id, s.ticket_number, s.occurred_at, u.display_name AS cashier, s.origin, s.status,
                               s.total_gross_cents, s.total_discount_cents, s.total_net_cents, s.anomalies,
                               s.cancelled_at, uc.display_name AS cancelled_by, s.cancel_reason
                        FROM sale s
                        LEFT JOIN app_user u ON u.id = s.cashier_user_id
                        LEFT JOIN app_user uc ON uc.id = s.cancelled_by
                        WHERE s.cash_session_id = :session
                        ORDER BY s.occurred_at, s.ticket_number""")
                .param("session", sessionId)
                .query((rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ArchivedSale(id, rs.getString("ticket_number"), instant(rs, "occurred_at"),
                            rs.getString("cashier"), rs.getString("origin"), rs.getString("status"),
                            rs.getLong("total_gross_cents"), rs.getLong("total_discount_cents"),
                            rs.getLong("total_net_cents"), rs.getString("anomalies"), instant(rs, "cancelled_at"),
                            rs.getString("cancelled_by"), rs.getString("cancel_reason"),
                            List.copyOf(paymentsBySale.getOrDefault(id, List.of())));
                })
                .list();
        List<ArchivedReturn> returns = jdbc.sql("""
                        SELECT r.id, s.ticket_number, r.occurred_at, u.display_name, r.refund_cents, r.reason
                        FROM sale_return r
                        JOIN sale s ON s.id = r.sale_id
                        LEFT JOIN app_user u ON u.id = r.recorded_by
                        WHERE r.cash_session_id = :session
                        ORDER BY r.occurred_at""")
                .param("session", sessionId)
                .query((rs, rowNum) -> new ArchivedReturn(rs.getObject("id", UUID.class), rs.getString("ticket_number"),
                        instant(rs, "occurred_at"), rs.getString("display_name"), rs.getLong("refund_cents"),
                        rs.getString("reason")))
                .list();
        List<ArchivedMovement> movements = jdbc.sql("""
                        SELECT m.kind, m.amount_cents, m.reason, m.expense_account, u.display_name, m.occurred_at
                        FROM cash_movement m LEFT JOIN app_user u ON u.id = m.recorded_by
                        WHERE m.cash_session_id = :session
                        ORDER BY m.occurred_at""")
                .param("session", sessionId)
                .query((rs, rowNum) -> new ArchivedMovement(rs.getString("kind"), rs.getLong("amount_cents"),
                        rs.getString("reason"), rs.getString("expense_account"), rs.getString("display_name"),
                        instant(rs, "occurred_at")))
                .list();
        List<ArchivedCollection> collections = jdbc.sql("""
                        SELECT c.display_name AS customer, p.method, p.provider, p.reference, p.amount_cents,
                               u.display_name AS received_by, p.received_at
                        FROM customer_payment p
                        JOIN customer c ON c.id = p.customer_id
                        LEFT JOIN app_user u ON u.id = p.received_by
                        WHERE p.cash_session_id = :session
                        ORDER BY p.received_at""")
                .param("session", sessionId)
                .query((rs, rowNum) -> new ArchivedCollection(rs.getString("customer"), rs.getString("method"),
                        rs.getString("provider"), rs.getString("reference"), rs.getLong("amount_cents"),
                        rs.getString("received_by"), instant(rs, "received_at")))
                .list();
        int cancelled = (int) sales.stream().filter(s -> "CANCELLED".equals(s.status())).count();
        long net = sales.stream().filter(s -> !"CANCELLED".equals(s.status())).mapToLong(ArchivedSale::netCents).sum();
        long refunds = returns.stream().mapToLong(ArchivedReturn::refundCents).sum();
        return new SessionArchive(sessionId, head.terminal(), head.openedBy(), head.openedAt(), head.closedBy(),
                head.closedAt(), head.floatCents(), head.expected(), head.counted(), head.difference(), head.notes(),
                head.hash(), sales.size() - cancelled, cancelled, net, refunds, sales, returns, movements, collections);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
