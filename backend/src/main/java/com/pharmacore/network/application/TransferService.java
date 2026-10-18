package com.pharmacore.network.application;

import com.pharmacore.inventory.application.StockLedger;
import com.pharmacore.network.domain.TransferChanged;
import com.pharmacore.network.domain.TransferParty;
import com.pharmacore.network.persistence.InterPharmacyTransferEntity;
import com.pharmacore.network.persistence.InterPharmacyTransferRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.web.BusinessRuleException;
import com.pharmacore.shared.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inter-pharmacy reservation workflow. Stock is only ever touched inside the provider's own
 * tenant context (RLS forbids anything else); when the requester triggers a release, the release
 * runs as a provider-scoped system step and is retried by {@link TransferReservationJob} if it
 * fails ({@code reservation_held} flag).
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final InterPharmacyTransferRepository transfers;
    private final StockLedger ledger;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final NetworkProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TransferService(InterPharmacyTransferRepository transfers, StockLedger ledger, AuditTrail audit,
                           ApplicationEventPublisher events, NetworkProperties properties,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.transfers = transfers;
        this.ledger = ledger;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public TransferView request(UUID networkId, UUID providerTenantId, UUID productId, int quantity, String note) {
        var user = TenantContext.requireTenantUser();
        return tx.execute(status -> {
            var transfer = transfers.save(new InterPharmacyTransferEntity(networkId, user.tenant().value(),
                    providerTenantId, productId, quantity, user.userId(), note, clock.instant()));
            transfers.flush();
            audit.record("TRANSFER_REQUESTED", "TRANSFER", transfer.id(), Map.of(
                    "provider", providerTenantId.toString(), "productId", productId.toString(),
                    "quantity", Integer.toString(quantity)));
            publish(transfer);
            return TransferView.of(transfer);
        });
    }

    public TransferView accept(UUID transferId, String note) {
        return tx.execute(status -> {
            var transfer = lockAs(transferId, TransferParty.PROVIDER);
            Instant now = clock.instant();
            ledger.reserve(transfer.productId(), transfer.quantity(), reference(transfer, now));
            transfer.accept(note, now, now.plus(properties.reservationTtl()));
            audit.record("TRANSFER_ACCEPTED", "TRANSFER", transferId, Map.of(
                    "expiresAt", transfer.reservationExpiresAt().toString()));
            publish(transfer);
            return TransferView.of(transfer);
        });
    }

    public TransferView decline(UUID transferId, String note) {
        return tx.execute(status -> {
            var transfer = lockAs(transferId, TransferParty.PROVIDER);
            transfer.decline(note, clock.instant());
            audit.record("TRANSFER_DECLINED", "TRANSFER", transferId, Map.of("note", String.valueOf(note)));
            publish(transfer);
            return TransferView.of(transfer);
        });
    }

    /** Provider hands the goods over: the reservation becomes a FEFO withdrawal. */
    public TransferView complete(UUID transferId) {
        return tx.execute(status -> {
            var transfer = lockAs(transferId, TransferParty.PROVIDER);
            Instant now = clock.instant();
            ledger.consumeReservation(transfer.productId(), transfer.quantity(), reference(transfer, now));
            transfer.complete(now);
            audit.record("TRANSFER_COMPLETED", "TRANSFER", transferId, Map.of(
                    "quantity", Integer.toString(transfer.quantity())));
            publish(transfer);
            return TransferView.of(transfer);
        });
    }

    public TransferView cancel(UUID transferId) {
        var view = tx.execute(status -> {
            var transfer = lockAs(transferId, TransferParty.REQUESTER);
            transfer.cancel();
            audit.record("TRANSFER_CANCELLED", "TRANSFER", transferId, Map.of());
            publish(transfer);
            return TransferView.of(transfer);
        });
        if (view.reservationHeld()) {
            releaseQuietly(transferId, new TenantId(view.providerTenantId()));
        }
        return view;
    }

    public List<TransferView> list(int limit) {
        var tenant = TenantContext.requireTenant().value();
        return tx.execute(status -> transfers.findForTenant(tenant, PageRequest.of(0, Math.clamp(limit, 1, 200)))
                .stream().map(TransferView::of).toList());
    }

    /**
     * Releases the provider-side reservation of a transfer that is no longer ACCEPTED, or expires
     * an ACCEPTED one whose deadline passed. Runs as a system process of the provider pharmacy.
     */
    public void releaseOrExpire(UUID transferId, TenantId provider) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("transfer-reservation", provider));
        TenantContext.run(context, () -> tx.executeWithoutResult(status -> {
            var transfer = transfers.lockById(transferId).orElseThrow(() -> new NotFoundException("Transfer", transferId));
            Instant now = clock.instant();
            boolean expired = transfer.status() == com.pharmacore.network.domain.TransferStatus.ACCEPTED
                    && transfer.reservationExpiresAt() != null && transfer.reservationExpiresAt().isBefore(now);
            if (expired) {
                transfer.expire();
                audit.record("TRANSFER_EXPIRED", "TRANSFER", transferId, Map.of());
                publish(transfer);
            }
            if (transfer.reservationHeld() && transfer.status() != com.pharmacore.network.domain.TransferStatus.ACCEPTED) {
                ledger.release(transfer.productId(), transfer.quantity(), reference(transfer, now));
                transfer.reservationReleased();
                audit.record("TRANSFER_RESERVATION_RELEASED", "TRANSFER", transferId, Map.of(
                        "quantity", Integer.toString(transfer.quantity())));
            }
        }));
    }

    private void releaseQuietly(UUID transferId, TenantId provider) {
        try {
            releaseOrExpire(transferId, provider);
        } catch (RuntimeException e) {
            log.warn("Reservation release of transfer {} deferred to the reconciliation job: {}", transferId,
                    e.getMessage());
        }
    }

    private InterPharmacyTransferEntity lockAs(UUID transferId, TransferParty expected) {
        var transfer = transfers.lockById(transferId).orElseThrow(() -> new NotFoundException("Transfer", transferId));
        if (transfer.partyOf(TenantContext.requireTenant().value()) != expected) {
            throw new BusinessRuleException("wrong-party", "Only the " + expected.name().toLowerCase()
                    + " pharmacy can perform this action");
        }
        return transfer;
    }

    private StockLedger.Reference reference(InterPharmacyTransferEntity transfer, Instant now) {
        UUID userId = TenantContext.actor() instanceof Actor.TenantUser user ? user.userId() : null;
        return new StockLedger.Reference("TRANSFER", transfer.id(), now, userId);
    }

    private void publish(InterPharmacyTransferEntity transfer) {
        events.publishEvent(new TransferChanged(transfer.id(), transfer.requesterTenantId(),
                transfer.providerTenantId(), transfer.productId(), transfer.quantity(), transfer.status()));
    }

    public record TransferView(UUID id, UUID networkId, UUID requesterTenantId, UUID providerTenantId,
                               UUID productId, int quantity, String status, String requesterNote,
                               String providerNote, Instant requestedAt, Instant respondedAt,
                               Instant reservationExpiresAt, boolean reservationHeld, Instant completedAt) {

        static TransferView of(InterPharmacyTransferEntity t) {
            return new TransferView(t.id(), t.networkId(), t.requesterTenantId(), t.providerTenantId(),
                    t.productId(), t.quantity(), t.status().name(), t.requesterNote(), t.providerNote(),
                    t.requestedAt(), t.respondedAt(), t.reservationExpiresAt(), t.reservationHeld(),
                    t.completedAt());
        }
    }
}
