package com.pharmacore.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pharmacore.catalog.domain.DrugListing;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Third-party payer coverage rule, as applied identically by the terminal and the server. */
class CoverageRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID POLICY = UUID.randomUUID();

    private final Map<UUID, CatalogFacts> catalog = Map.of(PRODUCT, new CatalogFacts(PRODUCT, 10_000, DrugListing.NONE, true));
    private final SellerProfile cashier = new SellerProfile(UUID.randomUUID(), Set.of("SALE_CREATE"), new BigDecimal("2"));

    @Test
    void expectedShareIsRoundedHalfUpAndCappedByTheCeiling() {
        var unlimited = new CoverageContext(POLICY, 80, null, true, true);
        assertThat(unlimited.expectedShareCents(10_000)).isEqualTo(8_000);
        assertThat(unlimited.expectedShareCents(10_001)).isEqualTo(8_001);
        assertThat(unlimited.expectedShareCents(1)).isEqualTo(1);
        assertThat(new CoverageContext(POLICY, 33, null, true, true).expectedShareCents(100)).isEqualTo(33);
        assertThat(new CoverageContext(POLICY, 80, 5_000L, true, true).expectedShareCents(10_000)).isEqualTo(5_000);
        assertThat(new CoverageContext(POLICY, 80, 0L, true, true).expectedShareCents(10_000)).isZero();
        assertThatThrownBy(() -> new CoverageContext(POLICY, 101, null, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchingShareIsAccepted() {
        var draft = draft(8_000, 2_000, CUSTOMER);
        var coverages = Map.of(POLICY, new CoverageContext(POLICY, 80, null, true, true));

        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW, coverages)).isEmpty();
    }

    @Test
    void shareDifferentFromThePolicyIsAMismatch() {
        var coverages = Map.of(POLICY, new CoverageContext(POLICY, 80, null, true, true));

        assertThat(SaleRules.evaluate(draft(9_000, 1_000, CUSTOMER), catalog, cashier, NOW, coverages))
                .containsExactly(SaleAnomaly.INSURANCE_SHARE_MISMATCH);
        // the monthly ceiling leaves only 3 000 to cover: the declared 8 000 is refused
        var capped = Map.of(POLICY, new CoverageContext(POLICY, 80, 3_000L, true, true));
        assertThat(SaleRules.evaluate(draft(8_000, 2_000, CUSTOMER), catalog, cashier, NOW, capped))
                .containsExactly(SaleAnomaly.INSURANCE_SHARE_MISMATCH);
        assertThat(SaleRules.evaluate(draft(3_000, 7_000, CUSTOMER), catalog, cashier, NOW, capped)).isEmpty();
    }

    @Test
    void unknownExpiredOrForeignPolicyIsInvalid() {
        var draft = draft(8_000, 2_000, CUSTOMER);

        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW, Map.of()))
                .containsExactly(SaleAnomaly.INSURANCE_POLICY_INVALID);
        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW,
                Map.of(POLICY, new CoverageContext(POLICY, 80, null, false, true))))
                .containsExactly(SaleAnomaly.INSURANCE_POLICY_INVALID);
        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW,
                Map.of(POLICY, new CoverageContext(POLICY, 80, null, true, false))))
                .containsExactly(SaleAnomaly.INSURANCE_POLICY_INVALID);
    }

    @Test
    void legacyThirdPartyShareWithoutPolicyIsNotChecked() {
        var draft = new SaleDraft(UUID.randomUUID(), "T01", "T01-000009", NOW, SaleOrigin.ONLINE, null, null,
                List.of(new SaleDraft.Line(PRODUCT, 1, 10_000, 0)),
                List.of(new SaleDraft.Payment(PaymentMethod.THIRD_PARTY_PAYER, 6_000, "AMO", null, null),
                        new SaleDraft.Payment(PaymentMethod.CASH, 4_000, null, null, null)), null);

        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW)).isEmpty();
        assertThat(draft.insuredSharesByPolicy()).isEmpty();
    }

    @Test
    void aPolicyOnlyBelongsToAThirdPartyShare() {
        assertThatThrownBy(() -> new SaleDraft.Payment(PaymentMethod.CASH, 100, null, null, null, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SaleDraft draft(long insured, long cash, UUID customer) {
        return new SaleDraft(UUID.randomUUID(), "T01", "T01-000008", NOW, SaleOrigin.ONLINE, null, customer,
                List.of(new SaleDraft.Line(PRODUCT, 1, 10_000, 0)),
                List.of(new SaleDraft.Payment(PaymentMethod.THIRD_PARTY_PAYER, insured, null, null, null, POLICY),
                        new SaleDraft.Payment(PaymentMethod.CASH, cash, null, null, null)), null);
    }
}
