package com.pharmacore.insurance.application;

import com.pharmacore.insurance.persistence.InsurancePolicyEntity;
import com.pharmacore.insurance.persistence.InsurancePolicyRepository;
import com.pharmacore.insurance.persistence.InsurerEntity;
import com.pharmacore.insurance.persistence.InsurerRepository;
import com.pharmacore.sales.domain.CoverageContext;
import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.tenant.persistence.TenantRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the policies a sale references into {@link CoverageContext}s for the domain rule, and
 * remembers which insurer account each covered share must be booked on.
 */
@Service
public class InsuranceCoverageResolver {

    private final InsurancePolicyRepository policies;
    private final InsurerRepository insurers;
    private final TenantRepository tenants;
    private final JdbcClient jdbc;

    public InsuranceCoverageResolver(InsurancePolicyRepository policies, InsurerRepository insurers,
                                     TenantRepository tenants, JdbcClient jdbc) {
        this.policies = policies;
        this.insurers = insurers;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    /**
     * @param contexts         rule input per referenced policy (absent = unknown policy)
     * @param insurerByPolicy  insurer carrying each resolved policy
     * @param accountByPolicy  receivable sub-account of that insurer
     */
    public record Resolution(Map<UUID, CoverageContext> contexts, Map<UUID, UUID> insurerByPolicy,
                             Map<UUID, String> accountByPolicy) {

        public static Resolution none() {
            return new Resolution(Map.of(), Map.of(), Map.of());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Resolution resolve(SaleDraft draft, LocalDate today) {
        Set<UUID> policyIds = draft.insuredSharesByPolicy().keySet();
        if (policyIds.isEmpty()) {
            return Resolution.none();
        }
        Map<UUID, InsurancePolicyEntity> found = policies.findByIdIn(policyIds).stream()
                .collect(Collectors.toMap(InsurancePolicyEntity::id, Function.identity()));
        Map<UUID, InsurerEntity> carriers = insurers.findAllById(found.values().stream()
                        .map(InsurancePolicyEntity::insurerId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(InsurerEntity::id, Function.identity()));
        Map<UUID, CoverageContext> contexts = new LinkedHashMap<>();
        Map<UUID, UUID> insurerByPolicy = new LinkedHashMap<>();
        Map<UUID, String> accountByPolicy = new LinkedHashMap<>();
        for (InsurancePolicyEntity policy : found.values()) {
            InsurerEntity insurer = carriers.get(policy.insurerId());
            boolean valid = insurer != null && insurer.active() && policy.isValidOn(today);
            boolean customerMatches = draft.customerId() != null && draft.customerId().equals(policy.customerId());
            contexts.put(policy.id(), new CoverageContext(policy.id(), policy.coverageRate(),
                    remainingCeiling(policy, today), valid, customerMatches));
            if (insurer != null) {
                insurerByPolicy.put(policy.id(), insurer.id());
                accountByPolicy.put(policy.id(), insurer.accountCode());
            }
        }
        return new Resolution(contexts, insurerByPolicy, accountByPolicy);
    }

    /** What the policy can still cover in the calendar month of {@code day}; {@code null} = unlimited. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long remainingCeiling(InsurancePolicyEntity policy, LocalDate day) {
        if (policy.monthlyCeilingCents() == null) {
            return null;
        }
        String zone = tenants.findById(TenantContext.requireTenant().value())
                .map(tenant -> tenant.timeZone()).orElse("UTC");
        long used = jdbc.sql("""
                        SELECT coalesce(sum(sp.amount_cents), 0)
                        FROM sale_payment sp
                        JOIN sale s ON s.id = sp.sale_id
                        WHERE sp.policy_id = :policy
                          AND s.status = 'COMPLETED'
                          AND date_trunc('month', s.occurred_at AT TIME ZONE :zone) = date_trunc('month', :day::date::timestamp)""")
                .param("policy", policy.id())
                .param("zone", zone)
                .param("day", Date.valueOf(day))
                .query(Long.class)
                .single();
        return Math.max(policy.monthlyCeilingCents() - used, 0);
    }
}
