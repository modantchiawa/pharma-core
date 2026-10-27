package com.pharmacore.control.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskScoreTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void eachComponentIsProportionalUpToItsCap() {
        var none = RiskScore.of(new RiskFactors(ID, 0, false, false, 0, 0, 0, 0));
        assertThat(none.total()).isZero();

        var half = RiskScore.of(new RiskFactors(ID, 50_000, false, false, 1, 2.5, 30, 1));
        assertThat(half.value()).isEqualTo(10);
        assertThat(half.discrepancies()).isEqualTo(8);
        assertThat(half.velocity()).isEqualTo(5);
        assertThat(half.staleness()).isEqualTo(8);
        assertThat(half.adjustments()).isEqualTo(5);

        var capped = RiskScore.of(new RiskFactors(ID, 900_000, false, false, 9, 40, 400, 9));
        assertThat(capped.value()).isEqualTo(20);
        assertThat(capped.discrepancies()).isEqualTo(15);
        assertThat(capped.velocity()).isEqualTo(10);
        assertThat(capped.staleness()).isEqualTo(15);
        assertThat(capped.adjustments()).isEqualTo(15);
    }

    @Test
    void narcoticOutweighsTheSafeAndNeverCountedIsStale() {
        assertThat(RiskScore.of(new RiskFactors(ID, 0, true, true, 0, 0, 0, 0)).custody()).isEqualTo(25);
        assertThat(RiskScore.of(new RiskFactors(ID, 0, false, true, 0, 0, 0, 0)).custody()).isEqualTo(15);
        assertThat(RiskScore.of(new RiskFactors(ID, 0, false, false, 0, 0, null, 0)).staleness()).isEqualTo(15);
    }

    @Test
    void theMaximumIsOneHundred() {
        var worst = RiskScore.of(new RiskFactors(ID, 1_000_000, true, true, 5, 10, null, 5));
        assertThat(worst.total()).isEqualTo(100);
    }

    @Test
    void reasonsExplainTheScoreInFrench() {
        assertThat(RiskScore.reasons(new RiskFactors(ID, 150_000, true, true, 2, 4, null, 3)))
                .containsExactly("stupéfiant", "2 écart(s) d'inventaire passé(s)", "valeur élevée au coût",
                        "3 sortie(s) ou ajustement(s) manuel(s) récent(s)", "jamais compté",
                        "forte rotation (4,0/j)");
        assertThat(RiskScore.reasons(new RiskFactors(ID, 1_000, false, true, 0, 0.1, 45, 0)))
                .containsExactly("rangé au coffre", "non compté depuis 45 j");
        assertThat(RiskScore.reasons(new RiskFactors(ID, 1_000, false, false, 0, 0.1, 3, 0))).isEmpty();
    }
}
