package com.pharmacore.control.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.DoubleFunction;

/**
 * Explainable anomaly rules of the per-employee control dashboard. A metric is flagged when it
 * reaches an absolute minimum <em>and</em> stands well above the team: at least {@code factor}
 * times the median of the staff active in the period (a factor of 0 means the minimum alone
 * suffices, for events that should never happen). The sentence built with the flag says which
 * condition fired, with the figures, so the owner can check it.
 */
public final class AnomalyRules {

    private AnomalyRules() {
    }

    /** What the rule compares; the label is used in the explanation. */
    public enum Metric {
        CANCELLATIONS_AFTER_PAYMENT("annulation(s) après paiement"),
        CANCELLATION_RATE("% de ventes annulées"),
        DISCOUNTS("de remises accordées"),
        SUPERVISOR_OVERRIDES("approbation(s) de superviseur sollicitée(s)"),
        STOCK_LOSSES("de sorties et ajustements négatifs au coût"),
        RETURNS("retour(s) remboursé(s)"),
        CASH_VARIANCE("d'écarts de caisse cumulés"),
        COUNT_DISCREPANCIES("écart(s) au contrôle du jour pendant ses présences"),
        OUTSIDE_HOURS("opération(s) hors des heures d'activité"),
        SHORTFALL_SALES("vente(s) de produits sans stock");

        private final String label;

        Metric(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Rule(Metric metric, double minimum, double factor) {
    }

    public record Flag(boolean flagged, String explanation) {
    }

    /** Defaults; amounts are in minor units of the pharmacy currency (francs for XOF). */
    public static Rule defaultRule(Metric metric, long cashVarianceThresholdCents) {
        return switch (metric) {
            case CANCELLATIONS_AFTER_PAYMENT -> new Rule(metric, 3, 2);
            case CANCELLATION_RATE -> new Rule(metric, 10, 2);
            case DISCOUNTS -> new Rule(metric, 5_000, 2);
            case SUPERVISOR_OVERRIDES -> new Rule(metric, 3, 2);
            case STOCK_LOSSES -> new Rule(metric, 20_000, 2);
            case RETURNS -> new Rule(metric, 3, 2);
            case CASH_VARIANCE -> new Rule(metric, Math.max(1, cashVarianceThresholdCents), 0);
            case COUNT_DISCREPANCIES -> new Rule(metric, 2, 2);
            case OUTSIDE_HOURS -> new Rule(metric, 1, 0);
            case SHORTFALL_SALES -> new Rule(metric, 3, 2);
        };
    }

    /**
     * @param format renders a value of this metric (count, percentage or amount) in the sentence
     */
    public static Flag evaluate(Rule rule, double value, double teamMedian, DoubleFunction<String> format) {
        boolean aboveMinimum = value >= rule.minimum();
        boolean aboveTeam = rule.factor() <= 0 || value >= rule.factor() * teamMedian;
        String figure = format.apply(value) + " " + rule.metric().label();
        if (aboveMinimum && aboveTeam) {
            String team = rule.factor() <= 0 ? ""
                    : ", et au moins " + trim(rule.factor()) + " fois la médiane de l'équipe ("
                            + format.apply(teamMedian) + ")";
            return new Flag(true, figure + " : le seuil d'examen est de " + format.apply(rule.minimum()) + team
                    + ".");
        }
        if (aboveMinimum) {
            return new Flag(false, figure + " : au-dessus du seuil, mais comparable à l'équipe (médiane "
                    + format.apply(teamMedian) + ").");
        }
        return new Flag(false, figure + " : sous le seuil d'examen (" + format.apply(rule.minimum()) + ").");
    }

    public static double median(Collection<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
