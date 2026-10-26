package com.pharmacore.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntryDraftTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    @Test
    void refusesUnbalancedEntries() {
        assertThatThrownBy(() -> new EntryDraft(Journal.VT, DAY, "Vente", List.of(
                EntryDraft.Line.debit(Accounts.CASH, 1_000, "Espèces"),
                EntryDraft.Line.credit(Accounts.SALES_DRUGS, 900, "Ventes"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void builderDropsZeroLinesAndReversalSwapsSides() {
        var entry = EntryDraft.builder(Journal.VT, DAY, "Vente")
                .debit(Accounts.CASH, 1_000, "Espèces")
                .credit(Accounts.VAT_COLLECTED, 0, "TVA")
                .credit(Accounts.SALES_DRUGS, 1_000, "Ventes")
                .build();

        assertThat(entry.lines()).hasSize(2);
        assertThat(entry.totalDebit()).isEqualTo(1_000);

        var reversed = entry.reversed(DAY.plusDays(1), "Annulation");
        assertThat(reversed.lines().getFirst().creditCents()).isEqualTo(1_000);
        assertThat(reversed.lines().getLast().debitCents()).isEqualTo(1_000);
        assertThat(reversed.totalDebit()).isEqualTo(reversed.totalCredit());
    }

    @Test
    void splitsTaxInclusiveAmounts() {
        // 1 190 XOF TTC à 19 % = 1 000 HT + 190 TVA
        var split = VatSplit.ofInclusive(1_190, new BigDecimal("19"));
        assertThat(split.netCents()).isEqualTo(1_000);
        assertThat(split.vatCents()).isEqualTo(190);
        assertThat(VatSplit.ofInclusive(500, BigDecimal.ZERO)).isEqualTo(new VatSplit(500, 0));
        assertThat(VatSplit.ofExclusive(1_000, new BigDecimal("19")).vatCents()).isEqualTo(190);
    }

    @Test
    void paymentMethodsLandOnTheRightAccount() {
        assertThat(Accounts.settlementAccount(com.pharmacore.sales.domain.PaymentMethod.MOBILE_MONEY)).isEqualTo("5221");
        assertThat(Accounts.settlementAccount(com.pharmacore.sales.domain.PaymentMethod.CREDIT)).isEqualTo("4111");
        assertThat(Accounts.settlementAccount(com.pharmacore.sales.domain.PaymentMethod.THIRD_PARTY_PAYER)).isEqualTo("4112");
    }
}
