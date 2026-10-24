package com.pharmacore.importing.application;

import com.pharmacore.catalog.application.CatalogService;
import com.pharmacore.catalog.persistence.ProductEntity;
import com.pharmacore.catalog.persistence.ProductEntity.ProductData;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The national catalog is writable by the platform only (row-level policy). A pharmacy migrating
 * its product file still needs its unknown products created: that creation runs as a platform
 * process in its own transaction, so the connection carries the platform flag, and the row is
 * committed before the pharmacy's own stock row references it. A dry run rolls it back instead.
 */
@Component
class CatalogWriter {

    private final CatalogService catalog;
    private final TransactionTemplate txNew;

    CatalogWriter(CatalogService catalog, PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.txNew = new TransactionTemplate(transactionManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    ProductEntity upsert(String code, ProductData data, boolean dryRun) {
        if (TenantContext.actor().crossTenant()) {
            return catalog.upsert(code, data);
        }
        var context = RequestContext.of(Actor.SystemProcess.crossTenant("data-import"));
        return TenantContext.call(context, () -> txNew.execute(status -> {
            ProductEntity product = catalog.upsert(code, data);
            if (dryRun) {
                status.setRollbackOnly();
            }
            return product;
        }));
    }
}
