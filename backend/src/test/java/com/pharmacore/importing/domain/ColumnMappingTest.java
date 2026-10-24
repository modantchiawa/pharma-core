package com.pharmacore.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnMappingTest {

    @Test
    void recognisesFrenchHeadersOfACompetitorExport() {
        var mapping = ColumnMapping.detect(ImportKind.STOCK, List.of(
                "Code CIP", "Libellé", "N° de Lot", "Date de Péremption", "Qté en stock", "P.A. HT", "Prix de vente TTC (FCFA)"));

        assertThat(mapping.column("code")).contains(0);
        assertThat(mapping.column("designation")).contains(1);
        assertThat(mapping.column("lot")).contains(2);
        assertThat(mapping.column("expiry")).contains(3);
        assertThat(mapping.column("quantity")).contains(4);
        assertThat(mapping.column("unitCost")).contains(5);
        assertThat(mapping.column("sellingPrice")).contains(6);
        assertThat(mapping.missingRequired(ImportKind.STOCK)).isEmpty();
    }

    @Test
    void reportsMissingRequiredColumnsAndNeverMapsOneColumnTwice() {
        var mapping = ColumnMapping.detect(ImportKind.PRODUCTS, List.of("Prix", "Observations"));
        assertThat(mapping.column("sellingPrice")).contains(0);
        assertThat(mapping.missingRequired(ImportKind.PRODUCTS)).containsExactly("Code CIP13 ou EAN13", "Désignation");

        var customers = ColumnMapping.detect(ImportKind.CUSTOMERS, List.of("Client", "Tel", "Crédit", "Plafond"));
        assertThat(customers.column("name")).contains(0);
        assertThat(customers.column("phone")).contains(1);
        assertThat(customers.column("creditLimit")).contains(3);
        assertThat(customers.column("balance")).contains(2);
    }

    @Test
    void normalizesAccentsCaseAndPunctuation() {
        assertThat(ColumnMapping.normalize("  N° de LOT ")).isEqualTo("n de lot");
        assertThat(ColumnMapping.normalize("Désignation")).isEqualTo("designation");
        assertThat(ColumnMapping.normalize("Prix_de-vente (TTC)")).isEqualTo("prix de vente ttc");
    }
}
