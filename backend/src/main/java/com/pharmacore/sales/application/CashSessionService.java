package com.pharmacore.sales.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.sales.domain.CashReportHashing;
import com.pharmacore.sales.domain.Denominations;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.persistence.CashMovementEntity;
import com.pharmacore.sales.persistence.CashMovementRepository;
import com.pharmacore.sales.persistence.CashSessionEntity;
import com.pharmacore.sales.persistence.CashSessionRepository;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening/closing of the cash drawer per terminal, X/Z reports, cash movements and expenses.
 * <p>
 * Closing is blind: the cashier counts banknotes and coins and declares the totals received by
 * mobile money, card and cheque without seeing what the system expects (only a holder of
 * {@code CASH_APPROVE} sees the expected cash of an open session). The Z report then carries the
 * reconciliation per payment method, a difference above the pharmacy's tolerance waits for a
 * supervisor's signature, and every Z report is chained by hash to the previous one of the same
 * terminal.
 */
@Service
public class CashSessionService {

    private static final TypeReference<List<Denominations.Line>> LINES = new TypeReference<>() {
    };

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final SaleRepository sales;
    private final AccountingPoster accounting;
    private final TenantCalendar calendar;
    private final CashPolicyService policy;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public CashSessionService(CashSessionRepository sessions, CashMovementRepository movements, SaleRepository sales,
                              AccountingPoster accounting, TenantCalendar calendar, CashPolicyService policy,
                              AuditTrail audit, JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.sales = sales;
        this.accounting = accounting;
        this.calendar = calendar;
        this.policy = policy;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public enum ApprovalStatus {
        NOT_REQUIRED,
        PENDING,
        APPROVED
    }

    /** @param expectedCashCents {@code null} on an open session for a cashier (blind count) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Report(UUID sessionId, String terminalId, String status, Instant openedAt, Instant closedAt,
                         long openingFloatCents, int salesCount, int cancelledCount, long salesNetCents,
                         Map<PaymentMethod, Long> byPaymentMethod, Map<String, Long> mobileMoneyByProvider,
                         long customerPaymentsCashCents, long cashInCents, long cashOutCents, long expensesCents,
                         Long expectedCashCents, Long countedCashCents, Long differenceCents, String notes,
                         List<Movement> movements, List<Denominations.Line> countedBreakdown,
                         List<ReconciliationLine> reconciliation, ApprovalStatus approvalStatus, Instant approvedAt,
                         String approvalNote, String reportHash, String previousReportHash) {
    }

    public record Movement(UUID id, String kind, long amountCents, String reason, String expenseAccount,
                           Instant occurredAt) {
    }

    /** Totals declared by the cashier for one payment method (mobile money per operator). */
    public record Declared(PaymentMethod method, String provider, long amountCents) {
    }

    public record ReconciliationLine(PaymentMethod method, String provider, long expectedCents, long declaredCents,
                                     long differenceCents) {
    }

    /**
     * @param denominations    banknotes and coins counted; when empty, {@code countedCashCents} is used
     * @param countedCashCents total typed directly (legacy clients, or a drawer counted elsewhere)
     */
    public record Closing(List<Denominations.Line> denominations, Long countedCashCents, List<Declared> declared,
                          String notes) {

        public Closing {
            denominations = denominations == null ? List.of() : List.copyOf(denominations);
            declared = declared == null ? List.of() : List.copyOf(declared);
        }

        public long counted() {
            if (!denominations.isEmpty()) {
                return Denominations.total(denominations);
            }
            if (countedCashCents == null) {
                throw new BusinessRuleException("count-required", "Comptez les espèces (coupures) avant de clôturer");
            }
            return countedCashCents;
        }
    }

    @Transactional(readOnly = true)
    public Optional<CashSessionEntity> open(String terminalId) {
        return sessions.findOpen(terminalId);
    }

    /** The session a sale on {@code terminalId} at {@code at} belongs to, if one is open. */
    @Transactional(readOnly = true)
    public Optional<UUID> sessionFor(String terminalId, Instant at) {
        return sessions.findOpen(terminalId).filter(s -> !s.openedAt().isAfter(at)).map(CashSessionEntity::id);
    }

    /** True when the sale's drawer has already been closed (its Z report is final). */
    @Transactional(readOnly = true)
    public boolean isClosed(UUID sessionId) {
        return sessions.findById(sessionId).map(s -> s.status() == CashSessionEntity.Status.CLOSED).orElse(false);
    }

    @Transactional
    public Report openSession(String terminalId, long openingFloatCents) {
        if (sessions.findOpen(terminalId).isPresent()) {
            throw new BusinessRuleException("session-open", "Une session est déjà ouverte sur ce poste");
        }
        var user = TenantContext.requireTenantUser();
        var session = sessions.save(new CashSessionEntity(terminalId, user.userId(), openingFloatCents, clock.instant()));
        audit.record("CASH_SESSION_OPENED", "CASH_SESSION", session.id(), Map.of(
                "terminal", terminalId, "floatCents", Long.toString(openingFloatCents)));
        return report(session, true);
    }

    @Transactional
    public Report closeSession(UUID sessionId, Closing closing) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Cash session", sessionId));
        if (session.status() == CashSessionEntity.Status.CLOSED) {
            throw new BusinessRuleException("session-closed", "Session déjà clôturée");
        }
        Report before = report(session, true);
        long expected = before.expectedCashCents();
        long counted = closing.counted();
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        session.close(user.userId(), expected, counted, closing.notes(), now);
        long difference = counted - expected;
        if (difference != 0) {
            accounting.post(Postings.sessionDifference(difference, session.terminalId(), calendar.today()),
                    "CASH_SESSION", session.id());
        }
        long tolerance = policy.current().cashDifferenceThresholdCents();
        if (tolerance > 0 && Math.abs(difference) > tolerance) {
            session.requireApproval();
        }
        List<ReconciliationLine> reconciliation = reconcile(session, before, counted, closing.declared());
        String previous = sessions.findClosedByTerminal(session.terminalId(), PageRequest.of(0, 2)).stream()
                .filter(s -> !s.id().equals(session.id()))
                .map(CashSessionEntity::reportHash)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(CashReportHashing.GENESIS);
        session.chain(previous, CashReportHashing.hash(session.id(), session.terminalId(), session.openingFloatCents(),
                before.salesNetCents(), expected, counted, now, previous));
        jdbc.sql("UPDATE cash_session SET counted_breakdown = :breakdown::jsonb WHERE id = :id")
                .param("breakdown", write(closing.denominations()))
                .param("id", session.id())
                .update();
        audit.record("CASH_SESSION_CLOSED", "CASH_SESSION", session.id(), Map.of(
                "expectedCents", Long.toString(expected),
                "countedCents", Long.toString(counted),
                "differenceCents", Long.toString(difference),
                "requiresApproval", Boolean.toString(session.closingRequiresApproval()),
                "methodsOff", Long.toString(reconciliation.stream().filter(l -> l.differenceCents() != 0).count()),
                "reportHash", session.reportHash()));
        return report(session, true);
    }

    /** Supervisor signature on a Z report whose difference exceeded the tolerance. */
    @Transactional
    public Report approveClosing(UUID sessionId, String note) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Cash session", sessionId));
        if (session.status() != CashSessionEntity.Status.CLOSED || !session.closingRequiresApproval()) {
            throw new BusinessRuleException("approval-not-needed", "Cette session n'attend pas d'approbation");
        }
        if (session.approvedAt() != null) {
            throw new BusinessRuleException("already-approved", "Cette clôture est déjà approuvée");
        }
        var user = TenantContext.requireTenantUser();
        session.approve(user.userId(), note, clock.instant());
        audit.record("CASH_SESSION_APPROVED", "CASH_SESSION", session.id(), Map.of(
                "differenceCents", String.valueOf(session.differenceCents()), "note", String.valueOf(note)));
        return report(session, true);
    }

    @Transactional
    public Movement recordMovement(UUID sessionId, CashMovementEntity.Kind kind, long amountCents, String reason,
                                   String expenseAccount) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Cash session", sessionId));
        if (session.status() != CashSessionEntity.Status.OPEN) {
            throw new BusinessRuleException("session-closed", "La session est clôturée");
        }
        if (kind == CashMovementEntity.Kind.EXPENSE && expenseAccount == null) {
            throw new BusinessRuleException("expense-account", "Indiquez le compte de charge de la dépense");
        }
        var user = TenantContext.requireTenantUser();
        var movement = movements.save(new CashMovementEntity(session.id(), kind, amountCents, reason, expenseAccount,
                user.userId(), clock.instant()));
        accounting.post(Postings.cashMovement(movement, calendar.today()), "CASH_MOVEMENT", movement.id());
        audit.record("CASH_MOVEMENT", "CASH_SESSION", session.id(), Map.of(
                "kind", kind.name(), "amountCents", Long.toString(amountCents), "reason", reason));
        return toView(movement);
    }

    /** Cash handed back on a cancellation: recorded as a drawer outflow, no extra accounting (the sale is reversed). */
    @Transactional
    public void recordRefund(UUID sessionId, long amountCents, String reason) {
        var user = TenantContext.requireTenantUser();
        movements.save(new CashMovementEntity(sessionId, CashMovementEntity.Kind.CASH_OUT, amountCents, reason, null,
                user.userId(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public Report report(UUID sessionId) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Cash session", sessionId));
        return report(session, canSeeExpected());
    }

    @Transactional(readOnly = true)
    public List<Report> recent(int limit) {
        boolean reveal = canSeeExpected();
        return sessions.findRecent(PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
                .map(s -> report(s, reveal)).toList();
    }

    @Transactional(readOnly = true)
    public List<Report> pendingApproval(int limit) {
        return sessions.findPendingApproval(PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
                .map(s -> report(s, true)).toList();
    }

    /** Expected cash of an open drawer is hidden from everyone but approvers (blind count). */
    private static boolean canSeeExpected() {
        return !(TenantContext.actor() instanceof Actor.TenantUser user) || user.can(SupervisorApprovalService.PERMISSION);
    }

    private List<ReconciliationLine> reconcile(CashSessionEntity session, Report before, long counted,
                                               List<Declared> declared) {
        Map<String, long[]> byKey = new LinkedHashMap<>();
        byKey.put(key(PaymentMethod.CASH, null), new long[] {before.expectedCashCents(), counted});
        before.byPaymentMethod().forEach((method, amount) -> {
            if (method != PaymentMethod.CASH && method != PaymentMethod.MOBILE_MONEY && method != PaymentMethod.CREDIT) {
                byKey.computeIfAbsent(key(method, null), k -> new long[2])[0] = amount;
            }
        });
        before.mobileMoneyByProvider().forEach((provider, amount) ->
                byKey.computeIfAbsent(key(PaymentMethod.MOBILE_MONEY, provider), k -> new long[2])[0] = amount);
        for (Declared line : declared) {
            if (line.method() == PaymentMethod.CASH || line.method() == PaymentMethod.CREDIT) {
                continue;
            }
            String provider = line.method() == PaymentMethod.MOBILE_MONEY ? line.provider() : null;
            byKey.computeIfAbsent(key(line.method(), provider), k -> new long[2])[1] += line.amountCents();
        }
        List<ReconciliationLine> lines = new ArrayList<>();
        byKey.forEach((key, amounts) -> {
            String[] parts = key.split("\\|", 2);
            PaymentMethod method = PaymentMethod.valueOf(parts[0]);
            String provider = parts[1].isEmpty() ? null : parts[1];
            lines.add(new ReconciliationLine(method, provider, amounts[0], amounts[1], amounts[1] - amounts[0]));
            jdbc.sql("""
                            INSERT INTO cash_session_reconciliation (id, tenant_id, cash_session_id, method, provider,
                                                                     expected_cents, declared_cents, difference_cents)
                            VALUES (:id, :tenant, :session, :method, :provider, :expected, :declared, :difference)""")
                    .param("id", UUID.randomUUID())
                    .param("tenant", TenantContext.requireTenant().value())
                    .param("session", session.id())
                    .param("method", method.name())
                    .param("provider", provider)
                    .param("expected", amounts[0])
                    .param("declared", amounts[1])
                    .param("difference", amounts[1] - amounts[0])
                    .update();
        });
        return lines;
    }

    private static String key(PaymentMethod method, String provider) {
        return method.name() + "|" + (provider == null ? "" : provider);
    }

    private Report report(CashSessionEntity session, boolean reveal) {
        List<SaleEntity> sessionSales = sales.findByCashSession(session.id());
        Map<PaymentMethod, Long> byMethod = new EnumMap<>(PaymentMethod.class);
        Map<String, Long> byProvider = new TreeMap<>();
        int completed = 0;
        int cancelled = 0;
        long net = 0;
        // Cash of every sale, cancelled ones included: a cancellation hands the cash back through a
        // CASH_OUT movement, so counting both sides keeps the expected drawer exact.
        long cashReceived = 0;
        for (SaleEntity sale : sessionSales) {
            cashReceived += sale.payments().stream().filter(p -> p.method() == PaymentMethod.CASH)
                    .mapToLong(SalePaymentEntity::amountCents).sum();
            if (sale.status() == SaleEntity.Status.CANCELLED) {
                cancelled++;
                continue;
            }
            completed++;
            net += sale.totalNetCents();
            for (SalePaymentEntity payment : sale.payments()) {
                byMethod.merge(payment.method(), payment.amountCents(), Long::sum);
                if (payment.method() == PaymentMethod.MOBILE_MONEY) {
                    byProvider.merge(payment.provider() == null ? "?" : payment.provider(), payment.amountCents(), Long::sum);
                }
            }
        }
        long customerCash = jdbc.sql("""
                        SELECT coalesce(sum(amount_cents), 0) FROM customer_payment
                        WHERE cash_session_id = :session AND method = 'CASH'""")
                .param("session", session.id()).query(Long.class).single();
        List<CashMovementEntity> moves = movements.findByCashSessionIdOrderByOccurredAt(session.id());
        long cashIn = moves.stream().filter(m -> m.kind() == CashMovementEntity.Kind.CASH_IN).mapToLong(CashMovementEntity::amountCents).sum();
        long cashOut = moves.stream().filter(m -> m.kind() == CashMovementEntity.Kind.CASH_OUT).mapToLong(CashMovementEntity::amountCents).sum();
        long expenses = moves.stream().filter(m -> m.kind() == CashMovementEntity.Kind.EXPENSE).mapToLong(CashMovementEntity::amountCents).sum();
        long expected = session.openingFloatCents() + cashReceived + customerCash + cashIn - cashOut - expenses;
        boolean closed = session.status() == CashSessionEntity.Status.CLOSED;
        Long shownExpected = closed || reveal ? expected : null;
        ApprovalStatus approval = !session.closingRequiresApproval() ? ApprovalStatus.NOT_REQUIRED
                : session.approvedAt() == null ? ApprovalStatus.PENDING : ApprovalStatus.APPROVED;
        return new Report(session.id(), session.terminalId(), session.status().name(), session.openedAt(),
                session.closedAt(), session.openingFloatCents(), completed, cancelled, net, byMethod, byProvider,
                customerCash, cashIn, cashOut, expenses, shownExpected, session.countedCashCents(),
                closed ? session.differenceCents() : null, session.notes(),
                moves.stream().map(CashSessionService::toView).toList(),
                closed ? breakdown(session.id()) : List.of(),
                closed ? reconciliation(session.id()) : List.of(),
                approval, session.approvedAt(), session.approvalNote(), session.reportHash(),
                session.previousReportHash());
    }

    private List<Denominations.Line> breakdown(UUID sessionId) {
        return jdbc.sql("SELECT counted_breakdown::text FROM cash_session WHERE id = :id")
                .param("id", sessionId)
                .query(String.class).optional()
                .map(this::read)
                .orElse(List.of());
    }

    private List<ReconciliationLine> reconciliation(UUID sessionId) {
        return jdbc.sql("""
                        SELECT method, provider, expected_cents, declared_cents, difference_cents
                        FROM cash_session_reconciliation WHERE cash_session_id = :id
                        ORDER BY method, provider NULLS FIRST""")
                .param("id", sessionId)
                .query((rs, rowNum) -> new ReconciliationLine(PaymentMethod.valueOf(rs.getString("method")),
                        rs.getString("provider"), rs.getLong("expected_cents"), rs.getLong("declared_cents"),
                        rs.getLong("difference_cents")))
                .list();
    }

    private String write(List<Denominations.Line> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the cash count", e);
        }
    }

    private List<Denominations.Line> read(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(text, LINES);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt cash count", e);
        }
    }

    private static Movement toView(CashMovementEntity m) {
        return new Movement(m.id(), m.kind().name(), m.amountCents(), m.reason(), m.expenseAccount(), m.occurredAt());
    }
}
