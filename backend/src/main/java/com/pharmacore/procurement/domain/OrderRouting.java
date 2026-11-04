package com.pharmacore.procurement.domain;

/** Which wholesaler ("centrale") a product of the order sheet is ordered from. */
public enum OrderRouting {
    /** The supplier marked preferred for the product, else the last one it was bought from. */
    PREFERRED,
    /**
     * Where the stock sold over the period came from: the supplier whose deliveries (lots)
     * were sold the most. Falls back to {@link #PREFERRED} for a product with no traced origin.
     */
    ORIGIN
}
