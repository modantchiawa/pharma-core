package com.pharmacore.safety.domain;

import java.util.List;
import java.util.Optional;

/**
 * Families of substances a patient commonly declares an allergy to. A family recognises its
 * members by ATC class and by name pattern, so "pénicillines" catches amoxicillin as well.
 */
public enum AllergyFamily {
    PENICILLINS("Pénicillines", List.of("J01C"), List.of("CILLINE"), List.of()),
    CEPHALOSPORINS("Céphalosporines", List.of("J01DB", "J01DC", "J01DD", "J01DE"), List.of(), List.of("CEF")),
    SULFONAMIDES("Sulfamides", List.of("J01E"), List.of("SULFA"), List.of()),
    QUINOLONES("Quinolones", List.of("J01M"), List.of("FLOXACINE"), List.of()),
    MACROLIDES("Macrolides", List.of("J01FA"), List.of("THROMYCINE"), List.of("AZITHROMYCINE", "SPIRAMYCINE")),
    NSAIDS("Anti-inflammatoires (AINS)", List.of("M01A"), List.of("PROFENE"),
            List.of("DICLOFENAC", "PIROXICAM", "MELOXICAM", "ACIDE MEFENAMIQUE", "NAPROXENE", "NIMESULIDE",
                    "CELECOXIB", "ACIDE NIFLUMIQUE", "INDOMETACINE", "ACECLOFENAC")),
    ASPIRIN("Aspirine", List.of("B01AC06", "N02BA01"), List.of(), List.of("ACIDE ACETYLSALICYLIQUE", "ASPIRINE")),
    PARACETAMOL("Paracétamol", List.of("N02BE01"), List.of(), List.of("PARACETAMOL")),
    CODEINE("Codéine", List.of(), List.of(), List.of("CODEINE")),
    METAMIZOLE("Métamizole (noramidopyrine)", List.of("N02BB02"), List.of(), List.of("METAMIZOLE", "NORAMIDOPYRINE")),
    IODINE("Iode", List.of(), List.of("IOD"), List.of()),
    TETRACYCLINES("Tétracyclines", List.of("J01AA"), List.of("CYCLINE"), List.of());

    private final String label;
    private final List<String> atcPrefixes;
    private final List<String> nameFragments;
    private final List<String> namePrefixes;

    AllergyFamily(String label, List<String> atcPrefixes, List<String> nameFragments, List<String> namePrefixes) {
        this.label = label;
        this.atcPrefixes = atcPrefixes;
        this.nameFragments = nameFragments;
        this.namePrefixes = namePrefixes;
    }

    public String label() {
        return label;
    }

    public static Optional<AllergyFamily> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (AllergyFamily family : values()) {
            if (family.name().equalsIgnoreCase(code.trim())) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }

    public boolean matches(List<String> components, String atcCode) {
        String atc = atcCode == null ? "" : SubstanceNames.normalize(atcCode);
        if (!atc.isEmpty() && atcPrefixes.stream().anyMatch(atc::startsWith)) {
            return true;
        }
        for (String component : components) {
            String compact = component.replace(" ", "");
            if (nameFragments.stream().anyMatch(compact::contains)) {
                return true;
            }
            if (namePrefixes.stream().anyMatch(prefix -> component.startsWith(prefix))) {
                return true;
            }
        }
        return false;
    }
}
