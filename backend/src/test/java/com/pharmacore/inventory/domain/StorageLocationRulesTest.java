package com.pharmacore.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StorageLocationRulesTest {

    private static final UUID SHELF = UUID.randomUUID();
    private static final UUID FRIDGE = UUID.randomUUID();

    @Test
    void codesAreUpperCaseWithoutAccentsAndSingleSpaced() {
        assertThat(LocationCode.normalize(" a3 ")).isEqualTo("A3");
        assertThat(LocationCode.normalize("réserve")).isEqualTo("RESERVE");
        assertThat(LocationCode.normalize("tiroir   2")).isEqualTo("TIROIR 2");
        assertThat(LocationCode.normalize("B-12/3")).isEqualTo("B-12/3");
    }

    @Test
    void codesRejectEmptyLongOrPunctuatedInput() {
        assertThatThrownBy(() -> LocationCode.normalize("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocationCode.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocationCode.normalize("ETAGERE DU FOND 12")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocationCode.normalize("A3 · bas")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocationCode.normalize("-A3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codeDerivedFromFreeTextMatchesTheMigrationRule() {
        assertThat(LocationCode.fromLabel("Rayon A")).contains("RAYON A");
        assertThat(LocationCode.fromLabel("Rayon A · étagère 3")).contains("RAYON A ETAGERE");
        assertThat(LocationCode.fromLabel("Frigo")).contains("FRIGO");
        assertThat(LocationCode.fromLabel(" ·· ")).isEmpty();
        assertThat(LocationCode.fromLabel(null)).isEmpty();
    }

    @Test
    void kindIsGuessedFromCommonFrenchLabels() {
        assertThat(StorageLocationKind.guess("Frigo")).isEqualTo(StorageLocationKind.FRIDGE);
        assertThat(StorageLocationKind.guess("Réfrigérateur 2-8 °C")).isEqualTo(StorageLocationKind.FRIDGE);
        assertThat(StorageLocationKind.guess("Coffre stupéfiants")).isEqualTo(StorageLocationKind.SAFE);
        assertThat(StorageLocationKind.guess("Tiroir 2")).isEqualTo(StorageLocationKind.DRAWER);
        assertThat(StorageLocationKind.guess("Réserve")).isEqualTo(StorageLocationKind.RESERVE);
        assertThat(StorageLocationKind.guess("Derrière le comptoir")).isEqualTo(StorageLocationKind.COUNTER);
        assertThat(StorageLocationKind.guess("Rayon B")).isEqualTo(StorageLocationKind.SHELF);
        assertThat(StorageLocationKind.guess("Étagère haute")).isEqualTo(StorageLocationKind.SHELF);
        assertThat(StorageLocationKind.guess("Vitrine")).isEqualTo(StorageLocationKind.OTHER);
        assertThat(StorageLocationKind.guess(null)).isEqualTo(StorageLocationKind.OTHER);
    }

    @Test
    void detailIsTrimmedAndOnlyKeptWithALocation() {
        assertThat(new Placement(SHELF, "  case 12 ").detail()).isEqualTo("case 12");
        assertThat(new Placement(SHELF, "   ").detail()).isNull();
        assertThat(new Placement(null, "case 12")).isEqualTo(Placement.none());
        assertThatThrownBy(() -> new Placement(SHELF, "x".repeat(Placement.DETAIL_MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bulkMoveKeepsTheDetailOnlyWhenTheProductStaysInPlace() {
        var placed = new Placement(SHELF, "case 12");

        assertThat(placed.movedTo(SHELF)).isEqualTo(placed);
        assertThat(placed.movedTo(FRIDGE)).isEqualTo(new Placement(FRIDGE, null));
        assertThat(placed.movedTo(null)).isEqualTo(Placement.none());
        assertThat(Placement.none().movedTo(FRIDGE)).isEqualTo(new Placement(FRIDGE, null));
    }
}
