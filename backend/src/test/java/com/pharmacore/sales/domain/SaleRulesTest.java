package com.pharmacore.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.catalog.domain.DrugListing;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaleRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final UUID PARACETAMOL = UUID.randomUUID();
    private static final UUID ANTIBIOTIC = UUID.randomUUID();
    private static final UUID MORPHINE = UUID.randomUUID();

    private final Map<UUID, CatalogFacts> catalog = Map.of(
            PARACETAMOL, new CatalogFacts(PARACETAMOL, 250, DrugListing.NONE, true),
            ANTIBIOTIC, new CatalogFacts(ANTIBIOTIC, 1_000, DrugListing.LIST_I, true),
            MORPHINE, new CatalogFacts(MORPHINE, 2_000, DrugListing.STUPEFIANT, true));

    private final SellerProfile cashier = new SellerProfile(UUID.randomUUID(), Set.of("SALE_CREATE"), new BigDecimal("2"));
    private final SellerProfile pharmacist = new SellerProfile(UUID.randomUUID(),
            Set.of("SALE_CREATE", "PRESCRIPTION_DISPENSE", "NARCOTIC_DISPENSE"), new BigDecimal("30"));

    @Test
    void compliantOtcSaleHasNoAnomaly() {
        var draft = draft(List.of(new SaleDraft.Line(PARACETAMOL, 2, 250, 0)), 500, null, NOW);

        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW)).isEmpty();
    }

    @Test
    void discountAboveRoleCeilingIsAnAbacViolation() {
        // 10% discount for a cashier capped at 2%
        var draft = draft(List.of(new SaleDraft.Line(PARACETAMOL, 4, 250, 100)), 900, null, NOW);

        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW)).containsExactly(SaleAnomaly.DISCOUNT_ABOVE_LIMIT);
        assertThat(SaleRules.evaluate(draft, catalog, pharmacist, NOW)).isEmpty();
    }

    @Test
    void listedDrugsNeedDispensingRightAndPrescription() {
        var draft = draft(List.of(new SaleDraft.Line(ANTIBIOTIC, 1, 1_000, 0)), 1_000, null, NOW);

        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW))
                .containsExactlyInAnyOrder(SaleAnomaly.MISSING_DISPENSING_RIGHT, SaleAnomaly.PRESCRIPTION_MISSING);
        var prescribed = draft(draft.lines(), 1_000, new SaleDraft.Prescription("Dr Martin", "10101010101", "P-1"), NOW);
        assertThat(SaleRules.evaluate(prescribed, catalog, pharmacist, NOW)).isEmpty();
    }

    @Test
    void narcoticsRequireTheirOwnPermission() {
        var preparer = new SellerProfile(UUID.randomUUID(), Set.of("SALE_CREATE", "PRESCRIPTION_DISPENSE"), BigDecimal.TEN);
        var draft = draft(List.of(new SaleDraft.Line(MORPHINE, 1, 2_000, 0)), 2_000,
                new SaleDraft.Prescription("Dr Martin", "10101010101", "P-2"), NOW);

        assertThat(SaleRules.evaluate(draft, catalog, preparer, NOW)).containsExactly(SaleAnomaly.MISSING_DISPENSING_RIGHT);
    }

    @Test
    void detectsPriceAndPaymentMismatchAndFutureTimestamp() {
        var draft = draft(List.of(new SaleDraft.Line(PARACETAMOL, 1, 199, 0)), 150, null,
                NOW.plus(Duration.ofHours(1)));

        assertThat(SaleRules.evaluate(draft, catalog, cashier, NOW)).containsExactlyInAnyOrder(
                SaleAnomaly.PRICE_MISMATCH, SaleAnomaly.PAYMENT_MISMATCH, SaleAnomaly.FUTURE_TIMESTAMP);
    }

    @Test
    void onlineViolationsAreRejectedButOfflineSalesAreKeptForReview() {
        Set<SaleAnomaly> anomalies = Set.of(SaleAnomaly.DISCOUNT_ABOVE_LIMIT);

        assertThat(SaleRules.decide(SaleOrigin.ONLINE, anomalies))
                .isInstanceOfSatisfying(SaleDecision.Reject.class,
                        reject -> assertThat(reject.violations()).isEqualTo(anomalies));
        assertThat(SaleRules.decide(SaleOrigin.OFFLINE_REPLAY, anomalies))
                .isInstanceOfSatisfying(SaleDecision.Accept.class,
                        accept -> assertThat(accept.reviewRequired()).isTrue());
        assertThat(SaleRules.decide(SaleOrigin.ONLINE, Set.of()))
                .isInstanceOfSatisfying(SaleDecision.Accept.class,
                        accept -> assertThat(accept.reviewRequired()).isFalse());
    }

    private static SaleDraft draft(List<SaleDraft.Line> lines, long paid, SaleDraft.Prescription prescription,
                                   Instant occurredAt) {
        return new SaleDraft(UUID.randomUUID(), "T01", "T01-000001", occurredAt, SaleOrigin.OFFLINE_REPLAY, null,
                lines, List.of(new SaleDraft.Payment(PaymentMethod.CASH, paid, null, null)), prescription);
    }
}
