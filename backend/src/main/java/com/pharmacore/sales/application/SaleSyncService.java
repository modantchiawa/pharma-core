package com.pharmacore.sales.application;

import com.pharmacore.sales.domain.SaleDraft;
import com.pharmacore.sales.domain.SaleOutcome;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.subscription.application.LicenseStateService;
import com.pharmacore.subscription.domain.LicenseState;
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
 * <p>
 * While the pharmacy's subscription is blocked, a sale that happened before the block is still
 * recorded (the goods left the shelf and the cash was taken while the pharmacy was entitled to
 * sell); a later one, or any sale when the block cannot be dated (clock tampered with, no
 * license), is answered RETRY: the terminal keeps it in its journal and it is recorded once the
 * subscription is regularized, never lost and never recorded while blocked.
 */
@Service
public class SaleSyncService {

    private static final Logger log = LoggerFactory.getLogger(SaleSyncService.class);

    static final String HELD_WHILE_BLOCKED =
            "Abonnement bloqué : vente conservée sur le poste, elle sera transmise après régularisation";

    private final SaleRecordingService recording;
    private final SaleRepository sales;
    private final LicenseStateService licenses;

    public SaleSyncService(SaleRecordingService recording, SaleRepository sales, LicenseStateService licenses) {
        this.recording = recording;
        this.sales = sales;
        this.licenses = licenses;
    }

    public List<SaleOutcome> synchronize(List<SaleDraft> drafts) {
        LicenseState license = licenses.current(TenantContext.requireTenant());
        return drafts.stream()
                .sorted(Comparator.comparing(SaleDraft::occurredAt).thenComparing(SaleDraft::clientSaleId))
                .map(draft -> license.admitsSaleAt(draft.occurredAt())
                        ? replay(draft)
                        : new SaleOutcome.Retry(draft.clientSaleId(), HELD_WHILE_BLOCKED))
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
