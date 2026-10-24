package com.pharmacore.importing.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Knobs of one run, chosen on the preview screen.
 *
 * @param dryRun          validate and count, write nothing
 * @param updateExisting  overwrite rows that already exist (products already in the catalog,
 *                        customers found by phone, suppliers found by name); otherwise they are
 *                        only completed where the import adds something (price, stock settings)
 * @param defaultLot      lot number used when the stock file has none ("MIGRATION")
 * @param defaultExpiry   expiry used when the stock file has none; null rejects such rows
 * @param defaultVatRate  VAT rate for products created without one (0 for exempt medicines)
 * @param currencyScale   decimals of the pharmacy currency (0 for XOF, 2 for EUR)
 */
public record ImportOptions(boolean dryRun, boolean updateExisting, String defaultLot, LocalDate defaultExpiry,
                            BigDecimal defaultVatRate, int currencyScale) {

    public ImportOptions {
        defaultLot = defaultLot == null || defaultLot.isBlank() ? "MIGRATION" : defaultLot.trim();
        defaultVatRate = defaultVatRate == null ? BigDecimal.ZERO : defaultVatRate;
    }

    public static ImportOptions defaults(int currencyScale) {
        return new ImportOptions(false, false, "MIGRATION", null, BigDecimal.ZERO, currencyScale);
    }
}
