package com.pharmacore.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Demonstration and QA mode: seeded pharmacies with known accounts and a password-based entry
 * to the platform console (no identity provider needed). Never enable on a production node.
 *
 * @param enabled          seed the demo pharmacies at startup (idempotent)
 * @param platformPassword password of the demo platform operator; blank disables the endpoint
 * @param accountPassword  password of every seeded staff account
 * @param pin              PIN of every seeded staff account
 */
@ConfigurationProperties(prefix = "pharmacore.demo")
public record DemoProperties(boolean enabled, String platformPassword, String accountPassword, String pin) {

    public DemoProperties {
        accountPassword = accountPassword == null || accountPassword.isBlank() ? "Demo2026!" : accountPassword;
        pin = pin == null || pin.isBlank() ? "1234" : pin;
    }

    public boolean platformLoginEnabled() {
        return platformPassword != null && !platformPassword.isBlank();
    }
}
