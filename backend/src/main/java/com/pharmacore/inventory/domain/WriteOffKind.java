package com.pharmacore.inventory.domain;

/** Why units leave the stock without being sold, as declared by hand. */
public enum WriteOffKind {
    CASSE("Casse"),
    /** Expiry noticed on the shelf before the nightly automatic exit. */
    PERIME("Périmé"),
    PERTE("Perte"),
    VOL("Vol");

    private final String label;

    WriteOffKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
