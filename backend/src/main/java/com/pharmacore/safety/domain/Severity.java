package com.pharmacore.safety.domain;

/** How serious a finding is, from the most to the least severe. */
public enum Severity {
    /** The pharmacist must not dispense unless they take the responsibility, with a reason. */
    CONTRAINDICATED("Contre-indication"),
    /** Dispensing is possible but the pharmacist should look for an alternative. */
    ADVISED_AGAINST("Association déconseillée"),
    /** Advice to give to the patient (spacing, monitoring, dose). */
    PRECAUTION("Précaution d'emploi");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean blocking() {
        return this == CONTRAINDICATED;
    }
}
