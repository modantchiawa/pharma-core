package com.pharmacore.sales.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.persistence.CashMovementEntity;
import com.pharmacore.sales.persistence.CashMovementRepository;
import com.pharmacore.sales.persistence.CashSessionEntity;
import com.pharmacore.sales.persistence.CashSessionRepository;
import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SalePaymentEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening/closing of the cash drawer per terminal, X/Z reports, cash movements and expenses. */
@Service
public class CashSessionService {

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final SaleRepository sales;
    private final AccountingPoster accounting;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CashSessionService(CashSessionRepository sessions, CashMovementRepository movements, SaleRepository sales,
                              AccountingPoster accounting, TenantCalendar calendar, AuditTrail audit, JdbcClient jdbc,
                              Clock clock) {
        this.sessions = sessions;
        this.movements = movements;
        this.sales = sales;
        this.accounting = accounting;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Report(UUID sessionId, String terminalId, String status, Instant openedAt, Instant closedAt,
                         long openingFloatCents, int salesCount, int cancelledCount, long salesNetCents,
                         Map<PaymentMethod, Long> byPaymentMethod, Map<String, Long> mobileMoneyByProvider,
                         long customerPaymentsCashCents, long cashInCents, long cashOutCents, long expensesCents,
                         long expectedCashCents, Long countedCashCents, Long differenceCents, String notes,
                         List<Movement> movements) {
    }

    public record Movement(UUID id, String kind, long amountCents, String reason, String expenseAccount,
                           Instant occurredAt) {
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

    @Transactional
    public Report openSession(String terminalId, long openingFloatCents) {
        if (sessions.findOpen(terminalId).isPresent()) {
            throw new BusinessRuleException("session-open", "Une session est déjà ouverte sur ce poste");
        }
        var user = TenantContext.requireTenantUser();
        var session = sessions.save(new CashSessionEntity(terminalId, user.userId(), openingFloatCents, clock.instant()));
        audit.record("CASH_SESSION_OPENED", "CASH_SESSION", session.id(), Map.of(
                "terminal", terminalId, "floatCents", Long.toString(openingFloatCents)));
        return report(session);
    }

    @Transactional
    public Report closeSession(UUID sessionId, long countedCashCents, String notes) {
        var session = sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Cash session", sessionId));
        if (session.status() == CashSessionEntity.Status.CLOSED) {
            throw new BusinessRuleException("session-closed", "Session déjà clôturée");
        }
        Report before = report(session);
        var user = TenantContext.requireTenantUser();
        session.close(user.userId(), before.expectedCashCents(), countedCashCents, notes, clock.instant());
        long difference = countedCashCents - before.expectedCashCents();
        if (difference != 0) {
            accounting.post(Postings.sessionDifference(difference, session.terminalId(), calendar.today()),
                    "CASH_SESSION", session.id());
        }
        audit.record("CASH_SESSION_CLOSED", "CASH_SESSION", session.id(), Map.of(
                "expectedCents", Long.toString(before.expectedCashCents()),
                "countedCents", Long.toString(countedCashCents),
                "differenceCents", Long.toString(difference)));
        return report(session);
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
        return report(sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("Cash session", sessionId)));
    }

    @Transactional(readOnly = true)
    public List<Report> recent(int limit) {
        return sessions.findRecent(PageRequest.of(0, Math.clamp(limit, 1, 100))).stream().map(this::report).toList();
    }

    private Report report(CashSessionEntity session) {
        List<SaleEntity> sessionSales = sales.findByCashSession(session.id());
        Map<PaymentMethod, Long> byMethod = new EnumMap<>(PaymentMethod.class);
        Map<String, Long> byProvider = new java.util.TreeMap<>();
        int completed = 0;
        int cancelled = 0;
        long net = 0;
        for (SaleEntity sale : sessionSales) {
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
        long expected = session.openingFloatCents() + byMethod.getOrDefault(PaymentMethod.CASH, 0L) + customerCash
                + cashIn - cashOut - expenses;
        return new Report(session.id(), session.terminalId(), session.status().name(), session.openedAt(),
                session.closedAt(), session.openingFloatCents(), completed, cancelled, net, byMethod, byProvider,
                customerCash, cashIn, cashOut, expenses, expected, session.countedCashCents(), session.differenceCents(),
                session.notes(), moves.stream().map(CashSessionService::toView).toList());
    }

    private static Movement toView(CashMovementEntity m) {
        return new Movement(m.id(), m.kind().name(), m.amountCents(), m.reason(), m.expenseAccount(), m.occurredAt());
    }
}
