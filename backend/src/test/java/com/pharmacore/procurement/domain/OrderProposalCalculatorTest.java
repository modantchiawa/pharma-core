package com.pharmacore.procurement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pharmacore.procurement.domain.OrderProposalCalculator.Input;
import com.pharmacore.procurement.domain.OrderProposalCalculator.Proposal;
import com.pharmacore.procurement.domain.OrderProposalCalculator.Reason;
import org.junit.jupiter.api.Test;

class OrderProposalCalculatorTest {

    @Test
    void coversDemandOverLeadTimeAndCoverageRoundedToPacks() {
        // 56 units sold in 28 days = 2/day; horizon 1 + 7 + 2 = 10 days → target 20; 6 on shelf
        var proposal = OrderProposalCalculator.propose(new Input(56, 0, 28, 6, 0, 0, 0, 0, 1, 7, 2, 12));

        assertThat(proposal.dailyDemand()).isEqualTo(2.0);
        assertThat(proposal.targetStock()).isEqualTo(20);
        assertThat(proposal.quantity()).isEqualTo(24);
        assertThat(proposal.reasons()).containsExactly(Reason.BELOW_TARGET);
    }

    @Test
    void countsOnOrderAndDoesNotOrderWhenCovered() {
        var proposal = OrderProposalCalculator.propose(new Input(56, 0, 28, 6, 20, 0, 0, 0, 1, 7, 2, 1));

        assertThat(proposal.needed()).isFalse();
        assertThat(proposal.projectedStock()).isEqualTo(26);
    }

    @Test
    void addsBackLostSalesAndReplacesExpiringUnits() {
        // offline shortfall of 14 units counts as demand; 5 units expire before they can sell
        var proposal = OrderProposalCalculator.propose(new Input(14, 14, 28, 10, 0, 5, 0, 0, 2, 5, 1, 1));

        assertThat(proposal.dailyDemand()).isEqualTo(1.0);
        assertThat(proposal.targetStock()).isEqualTo(8);
        assertThat(proposal.projectedStock()).isEqualTo(5);
        assertThat(proposal.quantity()).isEqualTo(3);
        assertThat(proposal.reasons()).containsExactlyInAnyOrder(Reason.BELOW_TARGET, Reason.EXPIRING_REPLACEMENT,
                Reason.LOST_SALES);
    }

    @Test
    void pharmacistThresholdWinsOverStatistics() {
        // slow mover (no sales) but the pharmacist wants at least 10 and orders 20 at a time
        var proposal = OrderProposalCalculator.propose(new Input(0, 0, 28, 4, 0, 0, 10, 20, 1, 7, 2, 1));

        assertThat(proposal.quantity()).isEqualTo(20);
        assertThat(proposal.reasons()).containsExactly(Reason.BELOW_THRESHOLD);
    }

    @Test
    void nothingToOrderYieldsEmptyProposal() {
        Proposal proposal = OrderProposalCalculator.propose(new Input(0, 0, 28, 50, 0, 0, 0, 0, 1, 7, 2, 1));

        assertThat(proposal).isEqualTo(new Proposal(0, 0, 0, 50, java.util.List.of()));
    }
}
