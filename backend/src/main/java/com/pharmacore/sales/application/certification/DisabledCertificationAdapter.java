package com.pharmacore.sales.application.certification;

import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default adapter: no legal certification configured. A DGI connector replaces it by providing
 * another {@link CertifiedInvoicePort} bean and setting {@code pharmacore.invoicing.certification}.
 */
@Component
@ConditionalOnProperty(name = "pharmacore.invoicing.certification", havingValue = "NONE", matchIfMissing = true)
public class DisabledCertificationAdapter implements CertifiedInvoicePort {

    @Override
    public boolean required(PharmacyConfigEntity pharmacy) {
        return false;
    }

    @Override
    public Certification certify(SaleEntity sale, PharmacyConfigEntity pharmacy) {
        return new Certification.Rejected("Certification not configured");
    }
}
