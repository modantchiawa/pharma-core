package com.pharmacore.demo;

import com.pharmacore.catalog.domain.DrugListing;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.demo.DemoDataSeeder.Drug;
import com.pharmacore.inventory.application.StorageLocationService;
import com.pharmacore.inventory.domain.StorageLocationKind;
import com.pharmacore.inventory.persistence.StorageLocationEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Storage plan of a demo pharmacy: shelves by therapeutic family, a drawer for injectables, the
 * fridge, the narcotics safe, the reserve and the counter. Every demo product is put away.
 */
final class DemoStorageLocations {

    private static final List<StorageLocationEntity.Details> LOCATIONS = List.of(
            new StorageLocationEntity.Details("A1", "Rayon A · antalgiques", StorageLocationKind.SHELF, 10),
            new StorageLocationEntity.Details("A2", "Rayon A · anti-infectieux", StorageLocationKind.SHELF, 20),
            new StorageLocationEntity.Details("B1", "Rayon B · antipaludiques", StorageLocationKind.SHELF, 30),
            new StorageLocationEntity.Details("B2", "Rayon B · traitements chroniques", StorageLocationKind.SHELF, 40),
            new StorageLocationEntity.Details("C1", "Rayon C · sirops, aérosols et pommades", StorageLocationKind.SHELF, 50),
            new StorageLocationEntity.Details("T1", "Tiroir 1 · injectables", StorageLocationKind.DRAWER, 60),
            new StorageLocationEntity.Details("FRIGO", "Réfrigérateur 2-8 °C", StorageLocationKind.FRIDGE, 70),
            new StorageLocationEntity.Details("COFFRE", "Coffre des stupéfiants", StorageLocationKind.SAFE, 80),
            new StorageLocationEntity.Details("COMPTOIR", "Derrière le comptoir", StorageLocationKind.COUNTER, 90),
            new StorageLocationEntity.Details("RESERVE", "Réserve", StorageLocationKind.RESERVE, 100));

    private DemoStorageLocations() {
    }

    static void seed(StorageLocationService locations, List<ProductEntity> products, List<Drug> drugs) {
        Map<String, UUID> ids = new HashMap<>();
        LOCATIONS.forEach(details -> ids.put(details.code(), locations.create(details).id()));
        Map<String, Integer> filled = new HashMap<>();
        for (int i = 0; i < products.size(); i++) {
            String code = codeOf(drugs.get(i));
            int position = filled.merge(code, 1, Integer::sum);
            locations.assign(products.get(i).id(), ids.get(code), detail(code, position));
        }
    }

    private static String codeOf(Drug drug) {
        String dci = drug.dci();
        if (drug.listing() == DrugListing.STUPEFIANT) {
            return "COFFRE";
        }
        if (dci.contains("INSULINE")) {
            return "FRIGO";
        }
        if (drug.designation().contains("MOUSTIQUAIRE")) {
            return "RESERVE";
        }
        return switch (drug.form()) {
            case "Injectable" -> "T1";
            case "Sirop", "Aérosol", "Gel" -> "C1";
            case "Accessoire", "Sachet" -> "COMPTOIR";
            default -> {
                if (dci.contains("PARACETAMOL") || dci.contains("IBUPROFENE")) {
                    yield "A1";
                }
                if (dci.contains("AMOXICILLINE") || dci.contains("SULFAMETHOXAZOLE")) {
                    yield "A2";
                }
                if (dci.contains("ARTEMETHER") || dci.contains("CHLOROQUINE")) {
                    yield "B1";
                }
                if (dci.contains("METFORMINE") || dci.contains("AMLODIPINE") || dci.contains("OMEPRAZOLE")) {
                    yield "B2";
                }
                yield "COMPTOIR";
            }
        };
    }

    private static String detail(String code, int position) {
        return switch (code) {
            case "A1", "A2", "B1", "B2", "C1" -> "case " + position;
            case "T1" -> "bac " + position;
            case "FRIGO" -> "clayette " + position;
            default -> null;
        };
    }
}
