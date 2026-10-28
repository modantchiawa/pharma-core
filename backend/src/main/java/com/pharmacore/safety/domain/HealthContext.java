package com.pharmacore.safety.domain;

/** Situations of the patient a product may be contraindicated in. Codes are mirrored in {@code drug_interaction}. */
public enum HealthContext {
    PREGNANCY("Grossesse", false),
    BREASTFEEDING("Allaitement", false),
    CHILD("Enfant de moins de 12 ans", false),
    ELDERLY("Personne de 65 ans et plus", false),
    RENAL("Insuffisance rénale", true),
    HEPATIC("Insuffisance hépatique", true),
    G6PD("Déficit en G6PD", true),
    ASTHMA("Asthme", true),
    DIABETES("Diabète", true),
    HYPERTENSION("Hypertension", true),
    EPILEPSY("Épilepsie", true),
    ULCER("Ulcère gastro-duodénal", true),
    GLAUCOMA("Glaucome à angle fermé", true),
    SICKLE_CELL("Drépanocytose", true);

    private final String label;
    private final boolean condition;

    HealthContext(String label, boolean condition) {
        this.label = label;
        this.condition = condition;
    }

    public String label() {
        return label;
    }

    /** A chronic condition declared on the file, as opposed to a state derived from age, pregnancy or breastfeeding. */
    public boolean condition() {
        return condition;
    }
}
