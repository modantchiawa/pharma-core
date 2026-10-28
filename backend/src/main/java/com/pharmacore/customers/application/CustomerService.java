package com.pharmacore.customers.application;

import com.pharmacore.accounting.application.AccountingPoster;
import com.pharmacore.accounting.application.Postings;
import com.pharmacore.customers.persistence.CustomerEntity;
import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.sales.application.CashSessionService;
import com.pharmacore.sales.domain.PaymentMethod;
import com.pharmacore.sales.persistence.TenantSequenceRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    public static final String CODE_SEQUENCE = "CUSTOMER";

    private final CustomerRepository customers;
    private final TenantSequenceRepository sequences;
    private final CashSessionService cashSessions;
    private final AccountingPoster accounting;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, TenantSequenceRepository sequences,
                           CashSessionService cashSessions, AccountingPoster accounting, TenantCalendar calendar,
                           AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.customers = customers;
        this.sequences = sequences;
        this.cashSessions = cashSessions;
        this.accounting = accounting;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record CustomerView(UUID id, String code, String displayName, String phone, String email, String address,
                               long creditLimitCents, long creditBalanceCents, long availableCreditCents,
                               int loyaltyPoints, String notes, boolean active, boolean messagingConsent,
                               String preferredChannel) {

        static CustomerView of(CustomerEntity c) {
            var d = c.details();
            return new CustomerView(c.id(), c.code(), c.displayName(), c.phone(), d.email(), d.address(),
                    c.creditLimitCents(), c.creditBalanceCents(),
                    Math.max(c.creditLimitCents() - c.creditBalanceCents(), 0), c.loyaltyPoints(), d.notes(), c.active(),
                    c.messagingConsent(), c.preferredChannel().name());
        }
    }

    public record PaymentView(UUID id, long amountCents, String method, String provider, String reference,
                              Instant receivedAt, long remainingBalanceCents) {
    }

    @Transactional
    public CustomerView create(CustomerEntity.Details details) {
        String code = "C" + String.format("%06d", sequences.next(CODE_SEQUENCE));
        var customer = customers.save(new CustomerEntity(code, details, clock.instant()));
        audit.record("CUSTOMER_CREATED", "CUSTOMER", customer.id(), Map.of("code", code, "name", details.displayName()));
        return CustomerView.of(customer);
    }

    @Transactional
    public CustomerView update(UUID id, CustomerEntity.Details details) {
        var customer = customers.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
        long previousLimit = customer.creditLimitCents();
        boolean previousConsent = customer.messagingConsent();
        customer.update(details);
        if (previousConsent != details.messagingConsent()) {
            audit.record("CUSTOMER_MESSAGING_CONSENT", "CUSTOMER", id, Map.of(
                    "consent", Boolean.toString(details.messagingConsent())));
        }
        if (previousLimit != details.creditLimitCents()) {
            audit.record("CUSTOMER_CREDIT_LIMIT_CHANGED", "CUSTOMER", id, Map.of(
                    "from", Long.toString(previousLimit), "to", Long.toString(details.creditLimitCents())));
        }
        return CustomerView.of(customer);
    }

    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return CustomerView.of(customers.findById(id).orElseThrow(() -> new NotFoundException("Customer", id)));
    }

    @Transactional(readOnly = true)
    public List<CustomerView> search(String term, int limit) {
        return customers.search(term.trim(), PageRequest.of(0, Math.clamp(limit, 1, 50))).stream()
                .map(CustomerView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerView> debtors(int limit) {
        return customers.findDebtors(PageRequest.of(0, Math.clamp(limit, 1, 500))).stream().map(CustomerView::of).toList();
    }

    /** Settles (part of) a customer's balance; cash goes into the terminal's open session. */
    @Transactional
    public PaymentView receivePayment(UUID customerId, long amountCents, PaymentMethod method, String provider,
                                      String reference, String terminalId) {
        if (method == PaymentMethod.CREDIT || method == PaymentMethod.THIRD_PARTY_PAYER) {
            throw new BusinessRuleException("invalid-method", "Moyen de règlement invalide pour une créance");
        }
        var customer = customers.lockById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (amountCents <= 0 || amountCents > customer.creditBalanceCents()) {
            throw new BusinessRuleException("invalid-amount",
                    "Le règlement doit être compris entre 1 et le solde dû (" + customer.creditBalanceCents() + ")");
        }
        var user = TenantContext.requireTenantUser();
        Instant now = clock.instant();
        UUID session = terminalId == null ? null : cashSessions.sessionFor(terminalId, now).orElse(null);
        if (method == PaymentMethod.CASH && session == null) {
            throw new BusinessRuleException("no-cash-session", "Ouvrez une session de caisse pour encaisser des espèces");
        }
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer_payment (id, tenant_id, customer_id, cash_session_id, method, provider,
                                                      amount_cents, reference, received_by, received_at)
                        VALUES (:id, :tenant, :customer, :session, :method, :provider, :amount, :reference, :by, :at)""")
                .param("id", paymentId)
                .param("tenant", user.tenant().value())
                .param("customer", customerId)
                .param("session", session)
                .param("method", method.name())
                .param("provider", provider)
                .param("amount", amountCents)
                .param("reference", reference)
                .param("by", user.userId())
                .param("at", Timestamp.from(now))
                .update();
        customer.settle(amountCents);
        accounting.post(Postings.customerPayment(amountCents, method, provider, customer.displayName(), calendar.today()),
                "CUSTOMER_PAYMENT", paymentId);
        audit.record("CUSTOMER_PAYMENT", "CUSTOMER", customerId, Map.of(
                "amountCents", Long.toString(amountCents), "method", method.name(),
                "remainingCents", Long.toString(customer.creditBalanceCents())));
        return new PaymentView(paymentId, amountCents, method.name(), provider, reference, now, customer.creditBalanceCents());
    }

    /** Called by sale recording: increases the balance, reports whether the limit is respected. */
    @Transactional
    public boolean addCredit(UUID customerId, long cents) {
        var customer = customers.lockById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (!customer.active()) {
            throw new BusinessRuleException("customer-inactive", "Client désactivé");
        }
        return customer.addCredit(cents);
    }

    /**
     * Opening balance carried over from the previous software: the amount the customer already
     * owes, recorded outside any sale and above the limit if need be (it is history, not a new credit).
     */
    @Transactional
    public void importOpeningBalance(UUID customerId, long cents) {
        var customer = customers.lockById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (cents == 0) {
            return;
        }
        customer.addCredit(cents);
        audit.record("CUSTOMER_OPENING_BALANCE", "CUSTOMER", customerId, Map.of("cents", Long.toString(cents)));
    }

    /** Existing customer with this phone number, for de-duplication when a file is imported. */
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> findIdByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return java.util.Optional.empty();
        }
        return jdbc.sql("SELECT id FROM customer WHERE tenant_id = :tenant AND phone = :phone ORDER BY created_at LIMIT 1")
                .param("tenant", TenantContext.requireTenant().value())
                .param("phone", phone.trim())
                .query(UUID.class).optional();
    }

    @Transactional
    public void releaseCredit(UUID customerId, long cents) {
        customers.lockById(customerId).ifPresent(customer -> customer.settle(cents));
    }
}
