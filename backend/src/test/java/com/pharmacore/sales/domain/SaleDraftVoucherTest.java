package com.pharmacore.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaleDraftVoucherTest {

    private final UUID product = UUID.randomUUID();

    @Test
    void aBonLeavesPartOfTheLineOwed() {
        var line = new SaleDraft.Line(product, 3, 1_000, 0, null, 2, null);
        assertThat(line.handedOverQuantity()).isEqualTo(1);
        assertThat(line.netCents()).isEqualTo(3_000);

        var draft = new SaleDraft(UUID.randomUUID(), "T1", "T1-1", Instant.now(), SaleOrigin.ONLINE, null, null,
                List.of(line), List.of(new SaleDraft.Payment(PaymentMethod.CASH, 3_000, null, null, null)), null,
                List.of(), new SaleDraft.VoucherHolder("  Awa ", " 90 11 22 33 "));
        assertThat(draft.leavesVouchers()).isTrue();
        assertThat(draft.voucherHolder().name()).isEqualTo("Awa");
        assertThat(draft.voucherHolder().reachable()).isTrue();
        assertThat(new SaleDraft.VoucherHolder("Awa", " ").reachable()).isFalse();
    }

    @Test
    void owedUnitsStayWithinTheLine() {
        assertThatThrownBy(() -> new SaleDraft.Line(product, 2, 1_000, 0, null, 3, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SaleDraft.Line(product, 2, 1_000, 0, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        // a line collecting a special order hands everything over
        assertThatThrownBy(() -> new SaleDraft.Line(product, 2, 1_000, 0, null, 1, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPlainSaleLeavesNothingOwed() {
        var draft = new SaleDraft(UUID.randomUUID(), "T1", "T1-1", Instant.now(), SaleOrigin.ONLINE, null, null,
                List.of(new SaleDraft.Line(product, 1, 500, 0)),
                List.of(new SaleDraft.Payment(PaymentMethod.CASH, 500, null, null, null)), null);
        assertThat(draft.leavesVouchers()).isFalse();
        assertThat(draft.voucherHolder()).isNull();
    }
}
