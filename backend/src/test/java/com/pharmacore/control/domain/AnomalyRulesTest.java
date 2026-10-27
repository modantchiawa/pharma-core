package com.pharmacore.control.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.control.domain.AnomalyRules.Metric;
import com.pharmacore.control.domain.AnomalyRules.Rule;
import java.util.List;
import java.util.function.DoubleFunction;
import org.junit.jupiter.api.Test;

class AnomalyRulesTest {

    private static final DoubleFunction<String> COUNT = value -> Long.toString((long) value);
    private static final DoubleFunction<String> MONEY = value -> Amounts.format(Math.round(value), "XOF");

    @Test
    void flaggedWhenAboveTheMinimumAndWellAboveTheTeam() {
        var rule = AnomalyRules.defaultRule(Metric.CANCELLATIONS_AFTER_PAYMENT, 2_000);
        var flag = AnomalyRules.evaluate(rule, 6, 1, COUNT);

        assertThat(flag.flagged()).isTrue();
        assertThat(flag.explanation()).isEqualTo("6 annulation(s) après paiement : le seuil d'examen est de 3, "
                + "et au moins 2 fois la médiane de l'équipe (1).");
    }

    @Test
    void notFlaggedBelowTheMinimumEvenFarAboveTheTeam() {
        var flag = AnomalyRules.evaluate(new Rule(Metric.RETURNS, 3, 2), 2, 0, COUNT);
        assertThat(flag.flagged()).isFalse();
        assertThat(flag.explanation()).isEqualTo("2 retour(s) remboursé(s) : sous le seuil d'examen (3).");
    }

    @Test
    void notFlaggedWhenTheWholeTeamDoesTheSame() {
        var flag = AnomalyRules.evaluate(new Rule(Metric.RETURNS, 3, 2), 5, 4, COUNT);
        assertThat(flag.flagged()).isFalse();
        assertThat(flag.explanation()).contains("comparable à l'équipe (médiane 4)");
    }

    @Test
    void aZeroFactorIgnoresTheTeam() {
        var rule = AnomalyRules.defaultRule(Metric.OUTSIDE_HOURS, 2_000);
        assertThat(AnomalyRules.evaluate(rule, 1, 3, COUNT).flagged()).isTrue();
        assertThat(AnomalyRules.evaluate(rule, 0, 0, COUNT).flagged()).isFalse();
    }

    @Test
    void cashVarianceUsesThePharmacyThresholdAndAmountsReadAsMoney() {
        var rule = AnomalyRules.defaultRule(Metric.CASH_VARIANCE, 2_000);
        var flag = AnomalyRules.evaluate(rule, 3_500, 0, MONEY);
        assertThat(flag.flagged()).isTrue();
        assertThat(flag.explanation()).isEqualTo("3 500 F CFA d'écarts de caisse cumulés : le seuil d'examen est de "
                + "2 000 F CFA.");
    }

    @Test
    void medianOfOddAndEvenTeams() {
        assertThat(AnomalyRules.median(List.of(5.0, 1.0, 3.0))).isEqualTo(3.0);
        assertThat(AnomalyRules.median(List.of(4.0, 1.0, 0.0, 10.0))).isEqualTo(2.5);
        assertThat(AnomalyRules.median(List.of())).isZero();
    }
}
