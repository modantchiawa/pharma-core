package com.pharmacore.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SafetyRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final UUID ASPIRIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID IBUPROFEN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID COLDREX = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DOLIPRANE = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID AUGMENTIN = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private static final DispensedItem aspirin = new DispensedItem(ASPIRIN, "ASPEGIC 1000", "Acide acétylsalicylique", "N02BA01");
    private static final DispensedItem ibuprofen = new DispensedItem(IBUPROFEN, "ADVIL 400", "Ibuprofène", "M01AE01");
    private static final DispensedItem coldrex = new DispensedItem(COLDREX, "COLDREX", "Paracétamol + Caféine + Phényléphrine", null);
    private static final DispensedItem doliprane = new DispensedItem(DOLIPRANE, "DOLIPRANE 1000", "PARACETAMOL", "N02BE01");
    private static final DispensedItem augmentin = new DispensedItem(AUGMENTIN, "AUGMENTIN 1 g", "Amoxicilline / acide clavulanique", "J01CR02");

    private static final List<SafetyRule> RULES = List.of(
            new SafetyRule(SafetyRule.Kind.INTERACTION, "IBUPROFENE", "ACIDE ACETYLSALICYLIQUE", Severity.ADVISED_AGAINST,
                    "Risque ulcéreux majoré."),
            new SafetyRule(SafetyRule.Kind.INTERACTION, "ATC:M01A", "ATC:M01A", Severity.ADVISED_AGAINST, "Deux AINS."),
            new SafetyRule(SafetyRule.Kind.CONTEXT, "ATC:M01A", "PREGNANCY", Severity.CONTRAINDICATED,
                    "AINS contre-indiqué dès le 6e mois."),
            new SafetyRule(SafetyRule.Kind.CONTEXT, "ACIDE ACETYLSALICYLIQUE", "CHILD", Severity.ADVISED_AGAINST,
                    "Syndrome de Reye."),
            new SafetyRule(SafetyRule.Kind.CONTEXT, "PARACETAMOL", "HEPATIC", Severity.PRECAUTION, "3 g par jour au plus."));

    @Test
    void normalisesNamesAndSplitsCombinations() {
        assertThat(SubstanceNames.components("Amoxicilline / acide clavulanique"))
                .containsExactly("AMOXICILLINE", "ACIDE CLAVULANIQUE");
        assertThat(SubstanceNames.components("Paracétamol + Caféine + Codéine (phosphate)"))
                .containsExactly("PARACETAMOL", "CAFEINE", "CODEINE");
        assertThat(SubstanceNames.matches("DICLOFENAC", List.of("DICLOFENAC SODIQUE"), null)).isTrue();
        assertThat(SubstanceNames.matches("DICLOFENAC", List.of("DICLOFENACINE"), null)).isFalse();
        assertThat(SubstanceNames.matches("ATC:M01A", List.of(), "M01AE01")).isTrue();
        assertThat(SubstanceNames.matches("ATC:M01A", List.of(), "N02BE01")).isFalse();
        assertThat(SubstanceNames.prefixes("ACIDE ACETYLSALICYLIQUE")).containsExactly("ACIDE", "ACIDE ACETYLSALICYLIQUE");
    }

    @Test
    void ruleOrdersItsSubstancesAndNormalisesThem() {
        var rule = new SafetyRule(SafetyRule.Kind.INTERACTION, "Warfarine", "acide acétylsalicylique", Severity.ADVISED_AGAINST, "x");

        assertThat(rule.substance()).isEqualTo("ACIDE ACETYLSALICYLIQUE");
        assertThat(rule.counterpart()).isEqualTo("WARFARINE");
    }

    @Test
    void findsInteractionsByNameAndByAtcClass() {
        List<SafetyAlert> alerts = SafetyRules.evaluateBasket(List.of(aspirin, ibuprofen), RULES);

        assertThat(alerts).extracting(SafetyAlert::kind).containsOnly(SafetyAlert.Kind.INTERACTION);
        assertThat(alerts).extracting(SafetyAlert::title)
                .contains("Interaction : ASPEGIC 1000 + ADVIL 400");
        assertThat(alerts).allSatisfy(alert -> assertThat(alert.productIds()).containsExactly(ASPIRIN, IBUPROFEN));
        assertThat(alerts.getFirst().key()).isEqualTo("INTERACTION:ACIDE ACETYLSALICYLIQUE|IBUPROFENE:" + ASPIRIN + "," + IBUPROFEN);
    }

    @Test
    void classRuleNeedsTwoDifferentProducts() {
        var rules = List.of(new SafetyRule(SafetyRule.Kind.INTERACTION, "ATC:M01A", "ATC:M01A", Severity.ADVISED_AGAINST, "Deux AINS."));

        assertThat(SafetyRules.evaluateBasket(List.of(ibuprofen), rules)).isEmpty();
        assertThat(SafetyRules.evaluateBasket(List.of(ibuprofen,
                new DispensedItem(UUID.randomUUID(), "VOLTARENE", "Diclofénac sodique", "M01AB05")), rules))
                .extracting(SafetyAlert::title).containsExactly("Même classe : ADVIL 400 + VOLTARENE");
    }

    @Test
    void flagsTheSameSubstanceHiddenInACombination() {
        List<SafetyAlert> alerts = SafetyRules.evaluateBasket(List.of(coldrex, doliprane), List.of());

        assertThat(alerts).hasSize(1);
        SafetyAlert alert = alerts.getFirst();
        assertThat(alert.kind()).isEqualTo(SafetyAlert.Kind.DUPLICATE_SUBSTANCE);
        assertThat(alert.severity()).isEqualTo(Severity.ADVISED_AGAINST);
        assertThat(alert.title()).isEqualTo("Doublon : PARACETAMOL dans 2 produits");
        assertThat(alert.productIds()).containsExactly(COLDREX, DOLIPRANE);
    }

    @Test
    void allergyFamilyCatchesMembersByClassAndByName() {
        var profile = new PatientProfile(null, false, false, List.of("PENICILLINS", "caféine"), Set.of());

        List<SafetyAlert> alerts = SafetyRules.evaluate(List.of(augmentin, coldrex), List.of(), profile, List.of(), TODAY);

        assertThat(alerts).extracting(SafetyAlert::kind).containsOnly(SafetyAlert.Kind.ALLERGY);
        assertThat(alerts).extracting(SafetyAlert::severity).containsOnly(Severity.CONTRAINDICATED);
        assertThat(alerts).extracting(SafetyAlert::title).containsExactlyInAnyOrder(
                "Allergie déclarée (Pénicillines) : AUGMENTIN 1 g",
                "Allergie déclarée (CAFEINE) : COLDREX");
        assertThat(alerts).allSatisfy(alert -> assertThat(alert.blocking()).isTrue());
    }

    @Test
    void contextsComeFromTheFileAndFromAge() {
        var pregnant = new PatientProfile(null, true, false, List.of(), Set.of());
        var child = new PatientProfile(TODAY.minusYears(8), false, false, List.of(), Set.of());
        var elderlyWithLiver = new PatientProfile(TODAY.minusYears(70), false, false, List.of(), Set.of(HealthContext.HEPATIC));

        assertThat(pregnant.contexts(TODAY)).containsExactly(HealthContext.PREGNANCY);
        assertThat(child.contexts(TODAY)).containsExactly(HealthContext.CHILD);
        assertThat(elderlyWithLiver.contexts(TODAY)).containsExactlyInAnyOrder(HealthContext.ELDERLY, HealthContext.HEPATIC);

        assertThat(SafetyRules.evaluate(List.of(ibuprofen), RULES, pregnant, List.of(), TODAY))
                .extracting(SafetyAlert::title, SafetyAlert::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Grossesse : ADVIL 400", Severity.CONTRAINDICATED));
        assertThat(SafetyRules.evaluate(List.of(aspirin), RULES, child, List.of(), TODAY))
                .extracting(SafetyAlert::title).containsExactly("Enfant de moins de 12 ans : ASPEGIC 1000");
        assertThat(SafetyRules.evaluate(List.of(doliprane), RULES, elderlyWithLiver, List.of(), TODAY))
                .extracting(SafetyAlert::title).containsExactly("Insuffisance hépatique : DOLIPRANE 1000");
    }

    @Test
    void recentDispensingOfTheSameSubstanceIsAPrecaution() {
        var recent = List.of(
                new RecentDispensing(UUID.randomUUID(), "EFFERALGAN 1000", "Paracétamol", null, TODAY.minusDays(2)),
                new RecentDispensing(UUID.randomUUID(), "ADVIL 400", "Ibuprofène", "M01AE01", TODAY.minusDays(20)));

        List<SafetyAlert> alerts = SafetyRules.evaluate(List.of(doliprane, ibuprofen), List.of(), PatientProfile.EMPTY, recent, TODAY);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().title()).isEqualTo("Déjà délivré il y a 2 jours : DOLIPRANE 1000");
        assertThat(alerts.getFirst().severity()).isEqualTo(Severity.PRECAUTION);
    }

    @Test
    void sortsMostSevereFirst() {
        var profile = new PatientProfile(null, true, false, List.of(), Set.of(HealthContext.HEPATIC));

        List<SafetyAlert> alerts = SafetyRules.evaluate(List.of(aspirin, ibuprofen, doliprane), RULES, profile, List.of(), TODAY);

        assertThat(alerts).extracting(SafetyAlert::severity)
                .isSortedAccordingTo(java.util.Comparator.naturalOrder());
        assertThat(alerts.getFirst().severity()).isEqualTo(Severity.CONTRAINDICATED);
    }
}
