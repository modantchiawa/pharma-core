package com.pharmacore.network.application;

import com.pharmacore.network.persistence.InterPharmacyTransferRepository;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Self-healing loop: expires overdue reservations and releases reservations left behind by a
 * failed release step. Discovery is cross-tenant; each fix runs in the provider's own context,
 * on its own virtual thread, with a row lock so concurrent nodes never process the same transfer
 * twice.
 */
@Component
class TransferReservationJob {

    private static final Logger log = LoggerFactory.getLogger(TransferReservationJob.class);
    private static final int BATCH = 200;

    private final InterPharmacyTransferRepository transfers;
    private final TransferService service;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    TransferReservationJob(InterPharmacyTransferRepository transfers, TransferService service,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.transfers = transfers;
        this.service = service;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pharmacore.network.reservation-sweep:PT1M}")
    void sweep() {
        var discovery = RequestContext.of(Actor.SystemProcess.crossTenant("transfer-reservation-sweep"));
        List<InterPharmacyTransferRepository.PendingRelease> pending = TenantContext.call(discovery,
                () -> readOnly.execute(status -> transfers.findReleasable(clock.instant(), PageRequest.of(0, BATCH))));
        if (pending.isEmpty()) {
            return;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var item : pending) {
                executor.submit(() -> {
                    try {
                        service.releaseOrExpire(item.getId(), new TenantId(item.getProviderTenantId()));
                    } catch (RuntimeException e) {
                        log.warn("Transfer {} reservation sweep failed, will retry: {}", item.getId(), e.getMessage());
                    }
                });
            }
        }
        log.info("Transfer reservation sweep processed {} transfers", pending.size());
    }
}
