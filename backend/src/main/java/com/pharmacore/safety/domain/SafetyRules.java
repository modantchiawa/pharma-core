package com.pharmacore.safety.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pure evaluation of a basket against the referential and the patient's file (no I/O). The
 * terminal runs the same logic on its offline copy of the referential for interactions and
 * duplicates; the server adds what only it knows (profile, dispensing history) and checks the
 * whole again when the sale is recorded.
 */
public final class SafetyRules {

    /** The same substance dispensed again within this delay is worth a word with the patient. */
    public static final int RECENT_DAYS = 7;

    private SafetyRules() {
    }

    public static List<SafetyAlert> evaluate(List<DispensedItem> items, List<SafetyRule> rules, PatientProfile profile,
                                             List<RecentDispensing> recent, LocalDate today) {
        List<SafetyAlert> alerts = new ArrayList<>();
        alerts.addAll(interactions(items, rules));
        alerts.addAll(duplicates(items));
        alerts.addAll(allergies(items, profile));
        alerts.addAll(contexts(items, rules, profile, today));
        alerts.addAll(recentDispensings(items, recent, today));
        return alerts.stream()
                .sorted(Comparator.comparing(SafetyAlert::severity).thenComparing(SafetyAlert::title))
                .toList();
    }

    /** Interactions and duplicates only: what a terminal can compute without the patient's file. */
    public static List<SafetyAlert> evaluateBasket(List<DispensedItem> items, List<SafetyRule> rules) {
        return evaluate(items, rules, PatientProfile.EMPTY, List.of(), LocalDate.EPOCH);
    }

    static List<SafetyAlert> interactions(List<DispensedItem> items, List<SafetyRule> rules) {
        List<SafetyAlert> alerts = new ArrayList<>();
        for (SafetyRule rule : rules) {
            if (rule.kind() != SafetyRule.Kind.INTERACTION) {
                continue;
            }
            List<DispensedItem> first = items.stream().filter(item -> item.matches(rule.substance())).toList();
            if (first.isEmpty()) {
                continue;
            }
            List<DispensedItem> second = rule.classDuplicate() ? first
                    : items.stream().filter(item -> item.matches(rule.counterpart())).toList();
            Set<UUID> involved = new LinkedHashSet<>();
            for (DispensedItem a : first) {
                for (DispensedItem b : second) {
                    if (!a.productId().equals(b.productId())) {
                        involved.add(a.productId());
                        involved.add(b.productId());
                    }
                }
            }
            if (involved.isEmpty()) {
                continue;
            }
            List<UUID> ids = sorted(involved);
            String names = items.stream().filter(item -> involved.contains(item.productId()))
                    .map(DispensedItem::designation).collect(Collectors.joining(" + "));
            String title = rule.classDuplicate()
                    ? "Même classe : " + names
                    : "Interaction : " + names;
            alerts.add(new SafetyAlert(SafetyAlert.Kind.INTERACTION, rule.severity(), title, rule.advice(), ids,
                    "INTERACTION:" + rule.substance() + "|" + rule.counterpart() + ":" + join(ids)));
        }
        return alerts;
    }

    static List<SafetyAlert> duplicates(List<DispensedItem> items) {
        Map<String, Set<UUID>> byComponent = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (DispensedItem item : items) {
            names.put(item.productId(), item.designation());
            for (String component : item.components()) {
                byComponent.computeIfAbsent(component, k -> new LinkedHashSet<>()).add(item.productId());
            }
        }
        List<SafetyAlert> alerts = new ArrayList<>();
        byComponent.forEach((component, ids) -> {
            if (ids.size() < 2) {
                return;
            }
            List<UUID> sorted = sorted(ids);
            String products = ids.stream().map(names::get).collect(Collectors.joining(", "));
            alerts.add(new SafetyAlert(SafetyAlert.Kind.DUPLICATE_SUBSTANCE, Severity.ADVISED_AGAINST,
                    "Doublon : " + component + " dans " + ids.size() + " produits",
                    "Risque de surdosage, la même substance est dans : " + products + ".",
                    sorted, "DUPLICATE_SUBSTANCE:" + component + ":" + join(sorted)));
        });
        return alerts;
    }

    static List<SafetyAlert> allergies(List<DispensedItem> items, PatientProfile profile) {
        List<SafetyAlert> alerts = new ArrayList<>();
        for (String declared : profile.allergies()) {
            Optional<AllergyFamily> family = AllergyFamily.parse(declared);
            String token = SubstanceNames.normalize(declared);
            String label = family.map(AllergyFamily::label).orElse(token);
            for (DispensedItem item : items) {
                List<String> components = item.components();
                boolean hit = family.map(f -> f.matches(components, item.atcCode()))
                        .orElseGet(() -> !token.isEmpty() && components.stream()
                                .anyMatch(c -> SubstanceNames.matchesComponent(token, c) || c.contains(token)));
                if (hit) {
                    alerts.add(new SafetyAlert(SafetyAlert.Kind.ALLERGY, Severity.CONTRAINDICATED,
                            "Allergie déclarée (" + label + ") : " + item.designation(),
                            "Le client a déclaré une allergie à : " + label + ". Vérifier avec lui avant toute délivrance.",
                            List.of(item.productId()),
                            "ALLERGY:" + family.map(Enum::name).orElse(token) + ":" + item.productId()));
                }
            }
        }
        return alerts;
    }

    static List<SafetyAlert> contexts(List<DispensedItem> items, List<SafetyRule> rules, PatientProfile profile,
                                      LocalDate today) {
        Set<HealthContext> active = profile.contexts(today);
        if (active.isEmpty()) {
            return List.of();
        }
        List<SafetyAlert> alerts = new ArrayList<>();
        for (SafetyRule rule : rules) {
            if (rule.kind() != SafetyRule.Kind.CONTEXT || !active.contains(rule.context())) {
                continue;
            }
            for (DispensedItem item : items) {
                if (item.matches(rule.substance())) {
                    alerts.add(new SafetyAlert(SafetyAlert.Kind.CONTEXT, rule.severity(),
                            rule.context().label() + " : " + item.designation(), rule.advice(),
                            List.of(item.productId()),
                            "CONTEXT:" + rule.counterpart() + ":" + rule.substance() + ":" + item.productId()));
                }
            }
        }
        return alerts;
    }

    static List<SafetyAlert> recentDispensings(List<DispensedItem> items, List<RecentDispensing> recent,
                                               LocalDate today) {
        List<SafetyAlert> alerts = new ArrayList<>();
        for (DispensedItem item : items) {
            List<String> components = item.components();
            Optional<RecentDispensing> latest = recent.stream()
                    .filter(r -> !r.dispensedOn().isBefore(today.minusDays(RECENT_DAYS)))
                    .filter(r -> r.productId().equals(item.productId())
                            || r.components().stream().anyMatch(components::contains))
                    .max(Comparator.comparing(RecentDispensing::dispensedOn));
            latest.ifPresent(r -> {
                long days = ChronoUnit.DAYS.between(r.dispensedOn(), today);
                String when = days == 0 ? "aujourd'hui" : days == 1 ? "hier" : "il y a " + days + " jours";
                alerts.add(new SafetyAlert(SafetyAlert.Kind.RECENT_DISPENSING, Severity.PRECAUTION,
                        "Déjà délivré " + when + " : " + item.designation(),
                        "Le client a reçu " + r.designation() + " le " + r.dispensedOn()
                                + ". Vérifier la prise en cours avant de délivrer à nouveau.",
                        List.of(item.productId()),
                        "RECENT_DISPENSING:" + item.productId() + ":" + r.dispensedOn()));
            });
        }
        return alerts;
    }

    private static List<UUID> sorted(Set<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private static String join(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
