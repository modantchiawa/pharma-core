package com.pharmacore.insurance.application;

import com.pharmacore.accounting.domain.Accounts;
import com.pharmacore.customers.persistence.CustomerRepository;
import com.pharmacore.insurance.persistence.InsurancePolicyEntity;
import com.pharmacore.insurance.persistence.InsurancePolicyRepository;
import com.pharmacore.insurance.persistence.InsurerEntity;
import com.pharmacore.insurance.persistence.InsurerRepository;
import com.pharmacore.inventory.application.TenantCalendar;
import com.pharmacore.sales.persistence.TenantSequenceRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Insurers and the policies binding them to customers. */
@Service
public class InsurerService {

    public static final String INSURER_SEQUENCE = "INSURER";

    private final InsurerRepository insurers;
    private final InsurancePolicyRepository policies;
    private final CustomerRepository customers;
    private final InsuranceCoverageResolver coverage;
    private final TenantSequenceRepository sequences;
    private final TenantCalendar calendar;
    private final AuditTrail audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public InsurerService(InsurerRepository insurers, InsurancePolicyRepository policies, CustomerRepository customers,
                          InsuranceCoverageResolver coverage, TenantSequenceRepository sequences,
                          TenantCalendar calendar, AuditTrail audit, JdbcClient jdbc, Clock clock) {
        this.insurers = insurers;
        this.policies = policies;
        this.customers = customers;
        this.coverage = coverage;
        this.sequences = sequences;
        this.calendar = calendar;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record InsurerView(UUID id, String code, String accountCode, boolean active, InsurerEntity.Details details) {

        static InsurerView of(InsurerEntity insurer) {
            return new InsurerView(insurer.id(), insurer.code(), insurer.accountCode(), insurer.active(),
                    insurer.details());
        }
    }

    /**
     * @param remainingCeilingCents what the policy can still cover this month, {@code null} when unlimited
     * @param usable                active, valid today and backed by an active insurer
     */
    public record PolicyView(UUID id, UUID insurerId, String insurerName, UUID customerId, String policyNumber,
                             String beneficiaryName, int coverageRate, Long monthlyCeilingCents,
                             Long remainingCeilingCents, LocalDate validFrom, LocalDate validUntil, boolean active,
                             boolean usable) {
    }

    @Transactional(readOnly = true)
    public List<InsurerView> list() {
        return insurers.findAllOrdered().stream().map(InsurerView::of).toList();
    }

    @Transactional(readOnly = true)
    public InsurerView get(UUID id) {
        return InsurerView.of(load(id));
    }

    /** Creates the insurer with its own receivable sub-account under 4112 (SYSCOHADA). */
    @Transactional
    public InsurerView create(InsurerEntity.Details details) {
        long number = sequences.next(INSURER_SEQUENCE);
        String code = "ASS" + String.format("%03d", number);
        String account = Accounts.THIRD_PARTY_PAYER_PREFIX + String.format("%03d", number);
        var insurer = insurers.save(new InsurerEntity(code, account, details, clock.instant()));
        jdbc.sql("""
                        INSERT INTO account (id, tenant_id, code, label, kind, system, active)
                        VALUES (:id, :tenant, :code, :label, 'ASSET', false, true)
                        ON CONFLICT (tenant_id, code) DO UPDATE SET active = true""")
                .param("id", UUID.randomUUID())
                .param("tenant", TenantContext.requireTenant().value())
                .param("code", account)
                .param("label", "Tiers payant - " + truncate(details.name(), 130))
                .update();
        audit.record("INSURER_CREATED", "INSURER", insurer.id(), Map.of(
                "code", code, "name", details.name(), "account", account,
                "rate", Integer.toString(details.defaultCoverageRate())));
        return InsurerView.of(insurer);
    }

    @Transactional
    public InsurerView update(UUID id, InsurerEntity.Details details) {
        var insurer = load(id);
        insurer.update(details);
        jdbc.sql("UPDATE account SET label = :label WHERE tenant_id = :tenant AND code = :code")
                .param("label", "Tiers payant - " + truncate(details.name(), 130))
                .param("tenant", TenantContext.requireTenant().value())
                .param("code", insurer.accountCode())
                .update();
        audit.record("INSURER_UPDATED", "INSURER", id, Map.of("name", details.name(),
                "rate", Integer.toString(details.defaultCoverageRate())));
        return InsurerView.of(insurer);
    }

    /** An insurer is never deleted (its history stays in the books); it stops covering new sales. */
    @Transactional
    public InsurerView setActive(UUID id, boolean active) {
        var insurer = load(id);
        insurer.setActive(active);
        audit.record(active ? "INSURER_REACTIVATED" : "INSURER_DEACTIVATED", "INSURER", id, Map.of("name", insurer.name()));
        return InsurerView.of(insurer);
    }

    @Transactional
    public PolicyView createPolicy(UUID insurerId, UUID customerId, InsurancePolicyEntity.Details details) {
        var insurer = load(insurerId);
        if (!insurer.active()) {
            throw new BusinessRuleException("insurer-inactive", "Assureur désactivé : aucune nouvelle police");
        }
        var customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (!customer.active()) {
            throw new BusinessRuleException("customer-inactive", "Client désactivé");
        }
        var policy = policies.save(new InsurancePolicyEntity(insurerId, customerId, details));
        audit.record("INSURANCE_POLICY_CREATED", "INSURANCE_POLICY", policy.id(), Map.of(
                "insurer", insurer.name(), "customer", customerId.toString(), "number", details.policyNumber(),
                "rate", Integer.toString(details.coverageRate())));
        return view(policy, insurer, calendar.today());
    }

    @Transactional
    public PolicyView updatePolicy(UUID policyId, InsurancePolicyEntity.Details details) {
        var policy = policies.findById(policyId).orElseThrow(() -> new NotFoundException("Insurance policy", policyId));
        policy.update(details);
        audit.record("INSURANCE_POLICY_UPDATED", "INSURANCE_POLICY", policyId, Map.of(
                "number", details.policyNumber(), "rate", Integer.toString(details.coverageRate()),
                "active", Boolean.toString(details.active())));
        return view(policy, load(policy.insurerId()), calendar.today());
    }

    @Transactional(readOnly = true)
    public List<PolicyView> policiesOfInsurer(UUID insurerId) {
        var insurer = load(insurerId);
        LocalDate today = calendar.today();
        return policies.findByInsurer(insurerId).stream().map(policy -> view(policy, insurer, today)).toList();
    }

    /** Policies the counter may apply to this customer right now, best rate first. */
    @Transactional(readOnly = true)
    public List<PolicyView> usablePoliciesOfCustomer(UUID customerId) {
        LocalDate today = calendar.today();
        return policies.findUsableByCustomer(customerId, today).stream()
                .map(policy -> view(policy, load(policy.insurerId()), today))
                .filter(PolicyView::usable)
                .toList();
    }

    private PolicyView view(InsurancePolicyEntity policy, InsurerEntity insurer, LocalDate today) {
        return new PolicyView(policy.id(), insurer.id(), insurer.name(), policy.customerId(), policy.policyNumber(),
                policy.beneficiaryName(), policy.coverageRate(), policy.monthlyCeilingCents(),
                coverage.remainingCeiling(policy, today), policy.validFrom(), policy.validUntil(), policy.active(),
                insurer.active() && policy.isValidOn(today));
    }

    private InsurerEntity load(UUID id) {
        return insurers.findById(id).orElseThrow(() -> new NotFoundException("Insurer", id));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
