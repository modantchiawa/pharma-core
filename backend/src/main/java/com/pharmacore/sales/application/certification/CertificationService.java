package com.pharmacore.sales.application.certification;

import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.sales.persistence.SaleRepository;
import com.pharmacore.shared.audit.AuditTrail;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.context.TenantId;
import com.pharmacore.shared.runtime.NodeIdentity;
import com.pharmacore.tenant.persistence.PharmacyConfigRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Background certification of pending sales (hub mode: the pharmacy it serves). */
@Service
public class CertificationService {

    private static final Logger log = LoggerFactory.getLogger(CertificationService.class);

    private final CertifiedInvoicePort port;
    private final SaleRepository sales;
    private final PharmacyConfigRepository configs;
    private final AuditTrail audit;
    private final NodeIdentity node;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CertificationService(CertifiedInvoicePort port, SaleRepository sales, PharmacyConfigRepository configs,
                                AuditTrail audit, NodeIdentity node, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.port = port;
        this.sales = sales;
        this.configs = configs;
        this.audit = audit;
        this.node = node;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public boolean required() {
        return configs.findCurrent().map(port::required).orElse(false);
    }

    @Scheduled(fixedDelayString = "${pharmacore.invoicing.retry-interval:PT1M}", initialDelayString = "PT1M")
    public void certifyPending() {
        node.hubTenant().ifPresent(this::certifyPending);
    }

    public int certifyPending(TenantId tenant) {
        var context = RequestContext.of(Actor.SystemProcess.forTenant("invoice-certification", tenant));
        return TenantContext.call(context, () -> {
            List<UUID> pending = tx.execute(status -> sales.findPendingCertification(PageRequest.of(0, 100))
                    .stream().map(SaleEntity::id).toList());
            int done = 0;
            for (UUID saleId : pending) {
                if (certifyOne(saleId)) {
                    done++;
                }
            }
            return done;
        });
    }

    private boolean certifyOne(UUID saleId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var sale = sales.findWithLines(saleId).orElse(null);
            var pharmacy = configs.findCurrent().orElse(null);
            if (sale == null || pharmacy == null) {
                return false;
            }
            switch (port.certify(sale, pharmacy)) {
                case CertifiedInvoicePort.Certification.Certified c -> {
                    sale.certified(c.code(), c.qrPayload(), clock.instant());
                    audit.record("SALE_CERTIFIED", "SALE", saleId, Map.of("code", c.code()));
                    return true;
                }
                case CertifiedInvoicePort.Certification.Unavailable u -> {
                    sale.certificationFailed(u.reason(), false);
                    log.info("Certification différée pour {} : {}", sale.ticketNumber(), u.reason());
                    return false;
                }
                case CertifiedInvoicePort.Certification.Rejected r -> {
                    sale.certificationFailed(r.reason(), true);
                    audit.record("SALE_CERTIFICATION_REJECTED", "SALE", saleId, Map.of("reason", r.reason()));
                    return false;
                }
            }
        }));
    }
}
