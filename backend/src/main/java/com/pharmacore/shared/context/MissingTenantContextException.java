package com.pharmacore.shared.context;

/** Raised when tenant-scoped code runs outside of a bound {@link TenantContext}. */
public class MissingTenantContextException extends IllegalStateException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
