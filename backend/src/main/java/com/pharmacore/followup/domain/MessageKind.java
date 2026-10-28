package com.pharmacore.followup.domain;

/**
 * Why the pharmacy writes to the patient. Each kind has a default template the pharmacy can
 * replace; placeholders are {@code {client}}, {@code {pharmacie}}, {@code {telephone}},
 * {@code {produit}}, {@code {montant}}, {@code {date}}.
 */
public enum MessageKind {
    TREATMENT_END("Fin de traitement",
            "Bonjour {client}, votre traitement {produit} se termine le {date}. Si vous devez le poursuivre, "
                    + "{pharmacie} vous attend. Tél. {telephone}"),
    RENEWAL_DUE("Renouvellement d'ordonnance",
            "Bonjour {client}, votre ordonnance peut être renouvelée à partir du {date}. "
                    + "{pharmacie} tient vos médicaments prêts : passez quand vous voulez. Tél. {telephone}"),
    PRODUCT_AVAILABLE("Produit attendu arrivé",
            "Bonjour {client}, bonne nouvelle : {produit} est de nouveau disponible chez {pharmacie}. "
                    + "Nous vous le gardons quelques jours. Tél. {telephone}"),
    CREDIT_REMINDER("Rappel de créance",
            "Bonjour {client}, {pharmacie} vous rappelle que votre compte présente un solde de {montant}. "
                    + "Merci de passer le régler quand vous le pourrez. Tél. {telephone}"),
    CUSTOM("Message libre", "{texte}");

    private final String label;
    private final String defaultTemplate;

    MessageKind(String label, String defaultTemplate) {
        this.label = label;
        this.defaultTemplate = defaultTemplate;
    }

    public String label() {
        return label;
    }

    public String defaultTemplate() {
        return defaultTemplate;
    }
}
