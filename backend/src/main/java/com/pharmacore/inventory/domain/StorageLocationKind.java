package com.pharmacore.inventory.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Nature of a storage place in the pharmacy; drives the icon and the storage conditions shown. */
public enum StorageLocationKind {
    /** Rayon, étagère, linéaire. */
    SHELF,
    /** Tiroir (injectables, ampoules, petits conditionnements). */
    DRAWER,
    /** Réfrigérateur 2-8 °C (insulines, vaccins). */
    FRIDGE,
    /** Coffre des stupéfiants. */
    SAFE,
    /** Réserve, arrière-boutique. */
    RESERVE,
    /** Comptoir ou derrière le comptoir. */
    COUNTER,
    OTHER;

    /**
     * Best guess from a free-text label taken over from an import or an older version ("Frigo",
     * "Tiroir 2", "Rayon B"); {@link #OTHER} when nothing matches.
     */
    public static StorageLocationKind guess(String label) {
        if (label == null) {
            return OTHER;
        }
        String text = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        if (text.contains("frigo") || text.contains("refrigerat")) {
            return FRIDGE;
        }
        if (text.contains("coffre") || text.contains("stup")) {
            return SAFE;
        }
        if (text.contains("tiroir")) {
            return DRAWER;
        }
        if (text.contains("reserve")) {
            return RESERVE;
        }
        if (text.contains("comptoir")) {
            return COUNTER;
        }
        if (text.contains("rayon") || text.contains("etagere") || text.contains("lineaire")) {
            return SHELF;
        }
        return OTHER;
    }
}
