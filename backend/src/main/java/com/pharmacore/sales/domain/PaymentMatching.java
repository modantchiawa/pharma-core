package com.pharmacore.sales.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which pending electronic payment a confirmation settles. Pure decision: the reference wins
 * when both sides carry one; otherwise the only pending payment of the operator for the same
 * amount in the window; two candidates mean "let the team choose" rather than a wrong match.
 */
public final class PaymentMatching {

    /** A mobile money or card payment still waiting for its confirmation. */
    public record Candidate(UUID paymentId, String provider, long amountCents, String reference) {
    }

    private PaymentMatching() {
    }

    public static Optional<UUID> choose(PaymentNotice notice, List<Candidate> pending) {
        if (!notice.usable()) {
            return Optional.empty();
        }
        List<Candidate> sameOperator = pending.stream()
                .filter(c -> notice.provider() == null || c.provider() == null || c.provider().equals(notice.provider()))
                .toList();
        if (notice.referenceOrEmpty().isPresent()) {
            String wanted = notice.referenceOrEmpty().get();
            List<Candidate> byReference = sameOperator.stream()
                    .filter(c -> c.reference() != null && c.reference().trim().equalsIgnoreCase(wanted))
                    .toList();
            if (byReference.size() == 1) {
                return Optional.of(byReference.getFirst().paymentId());
            }
            if (byReference.size() > 1) {
                return Optional.empty();
            }
        }
        List<Candidate> byAmount = sameOperator.stream()
                .filter(c -> c.amountCents() == notice.amountCents())
                // a payment typed with another reference belongs to another transaction
                .filter(c -> c.reference() == null || c.reference().isBlank() || notice.referenceOrEmpty().isEmpty())
                .toList();
        return byAmount.size() == 1 ? Optional.of(byAmount.getFirst().paymentId()) : Optional.empty();
    }
}
