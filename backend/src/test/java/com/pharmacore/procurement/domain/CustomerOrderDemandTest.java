package com.pharmacore.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.procurement.application.ProcurementService;
import com.pharmacore.procurement.application.ProcurementService.ProposalLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerOrderDemandTest {

    private static ProposalLine line(int suggested, int onOrder, int packSize) {
        return new ProposalLine(UUID.randomUUID(), "3400930000001", "PRODUIT", null, null, 0, onOrder, 0, 0, 0, 0.0,
                0, suggested, packSize, 100L, List.of(OrderProposalCalculator.Reason.BELOW_TARGET), 1.0,
                OrderRouting.PREFERRED);
    }

    @Test
    void waitingCustomersAddWhatTheOrdersOnTheWayDoNotCover() {
        ProposalLine result = ProcurementService.withCustomerOrders(line(4, 1, 1), 3);
        assertThat(result.suggestedQuantity()).isEqualTo(6);
        assertThat(result.reasons()).contains(OrderProposalCalculator.Reason.CUSTOMER_ORDER);
    }

    @Test
    void roundedUpToThePack() {
        assertThat(ProcurementService.withCustomerOrders(line(0, 0, 10), 3).suggestedQuantity()).isEqualTo(10);
    }

    @Test
    void coveredDemandChangesNothing() {
        ProposalLine original = line(5, 4, 1);
        assertThat(ProcurementService.withCustomerOrders(original, 4)).isSameAs(original);
        assertThat(ProcurementService.withCustomerOrders(original, 0)).isSameAs(original);
    }
}
