package com.pharmacore.shared.security;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Trusted OpenID Connect issuers. Typically two Keycloak realms: one for the software editor
 * (Super-Admin) and one shared by all pharmacies, whose tokens carry a {@code tenant_id} claim.
 * Dedicated realms per pharmacy chain can be appended without code change.
 */
@ConfigurationProperties("pharmacore.security")
public record SecurityProperties(List<Issuer> issuers, List<String> allowedOrigins) {

    public SecurityProperties {
        issuers = issuers == null ? List.of() : List.copyOf(issuers);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    public enum IssuerKind {
        PLATFORM,
        TENANT
    }

    public record Issuer(URI uri, IssuerKind kind, String audience) {
    }
}
