package com.pharmacore.sales.application;

import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOutcome;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.web.BusinessRuleException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/**
 * Replays a terminal's offline journal. Each sale is committed in its own transaction, in the
 * order it happened, so one problematic sale never blocks the others and a retried batch only
 * re-plays what was not acknowledged.
 */
@Service
public class SaleSyncService {

    private static final Logger log = LoggerFactory.getLogger(SaleSyncService.class);

    private final SaleRecordingService recording;
    private final SaleRepository sales;

    public SaleSyncService(SaleRecordingService recording, SaleRepository sales) {
        this.recording = recording;
        this.sales = sales;
    }

    public List<SaleOutcome> synchronize(List<SaleDraft> drafts) {
        return drafts.stream()
                .sorted(Comparator.comparing(SaleDraft::occurredAt).thenComparing(SaleDraft::clientSaleId))
                .map(this::replay)
                .toList();
    }

    private SaleOutcome replay(SaleDraft draft) {
        try {
            return recording.record(draft);
        } catch (DataIntegrityViolationException e) {
            // Same sale pushed concurrently by two sync loops: the unique key decided the winner.
            return sales.findIdByClientSaleId(draft.clientSaleId())
                    .<SaleOutcome>map(id -> new SaleOutcome.Duplicate(draft.clientSaleId(), id))
                    .orElseGet(() -> {
                        log.error("Integrity violation while replaying sale {}", draft.clientSaleId(), e);
                        return new SaleOutcome.Rejected(draft.clientSaleId(), "integrity-violation", Set.of(),
                                e.getMostSpecificCause().getMessage());
                    });
        } catch (BusinessRuleException e) {
            return new SaleOutcome.Rejected(draft.clientSaleId(), e.code(), Set.of(), e.getMessage());
        } catch (TransientDataAccessException e) {
            // Lock timeouts, serialization failures and optimistic conflicts are all retryable.
            log.warn("Transient failure while replaying sale {}: {}", draft.clientSaleId(), e.getMessage());
            return new SaleOutcome.Retry(draft.clientSaleId(), "Temporary failure, retry later");
        }
    }
}
