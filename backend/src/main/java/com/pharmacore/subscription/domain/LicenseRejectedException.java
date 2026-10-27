package com.pharmacore.subscription.domain;

/** A license a hub refuses to hold; the license it already holds stays in force. */
public class LicenseRejectedException extends RuntimeException {

    public enum Reason {
        MALFORMED,
        UNKNOWN_KEY,
        BAD_SIGNATURE,
        WRONG_TENANT,
        SERIAL_REGRESSION
    }

    private final Reason reason;

    public LicenseRejectedException(Reason reason) {
        super("License rejected: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
