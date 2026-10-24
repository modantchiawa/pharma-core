package com.pharmacore.shared.security;

import java.util.List;
import java.util.Optional;

public class CompositeTrustedIssuerRegistry implements TrustedIssuerRegistry {

    private final List<TrustedIssuerRegistry> registries;

    public CompositeTrustedIssuerRegistry(List<TrustedIssuerRegistry> registries) {
        this.registries = List.copyOf(registries);
    }

    @Override
    public Optional<TrustedIssuer> find(String issuer, String kid) {
        for (TrustedIssuerRegistry registry : registries) {
            var found = registry.find(issuer, kid);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
