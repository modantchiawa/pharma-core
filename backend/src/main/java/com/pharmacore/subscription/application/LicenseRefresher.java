package com.pharmacore.subscription.application;

/**
 * Port: fetches a fresh license from the cloud right away (hub only; implemented by the
 * synchronization, which also refreshes it on every cycle).
 */
public interface LicenseRefresher {

    /** @throws org.springframework.web.client.RestClientException when the cloud cannot be reached */
    void refresh();
}
