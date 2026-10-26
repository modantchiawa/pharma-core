package com.pharmacore.sales.application.certification;

import com.pharmacore.sales.persistence.SaleEntity;
import com.pharmacore.tenant.persistence.PharmacyConfigEntity;

/**
 * Outbound port to a legal e-invoicing system (Niger: DGI / SECeF certified invoices with a
 * DGI code and QR). The sale is recorded locally first; certification is asynchronous and
 * retried while the system is unreachable, which keeps the counter working offline.
 */
public interface CertifiedInvoicePort {

    /** Whether sales of this pharmacy must be certified at all. */
    boolean required(PharmacyConfigEntity pharmacy);

    Certification certify(SaleEntity sale, PharmacyConfigEntity pharmacy);

    sealed interface Certification {

        record Certified(String code, String qrPayload) implements Certification {
        }

        /** Temporary failure (network, service down): retried later. */
        record Unavailable(String reason) implements Certification {
        }

        /** The invoice was refused for good; a person must look at it. */
        record Rejected(String reason) implements Certification {
        }
    }
}
