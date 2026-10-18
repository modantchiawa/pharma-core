package com.pharmacore.shared.security;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Discovers JWKS endpoints lazily through OIDC discovery for each configured issuer. */
public class OidcTrustedIssuerRegistry implements TrustedIssuerRegistry {

    private final Map<String, SecurityProperties.Issuer> issuers;
    private final Map<String, TrustedIssuer> decoders = new ConcurrentHashMap<>();

    public OidcTrustedIssuerRegistry(SecurityProperties properties) {
        this.issuers = properties.issuers().stream()
                .collect(Collectors.toUnmodifiableMap(issuer -> issuer.uri().toString(), Function.identity()));
    }

    @Override
    public Optional<TrustedIssuer> find(String issuer) {
        if (issuer == null || !issuers.containsKey(issuer)) {
            return Optional.empty();
        }
        return Optional.of(decoders.computeIfAbsent(issuer, key -> build(issuers.get(key))));
    }

    private static TrustedIssuer build(SecurityProperties.Issuer issuer) {
        var location = issuer.uri().toString();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(location).build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(location);
        if (issuer.audience() != null && !issuer.audience().isBlank()) {
            validator = new DelegatingOAuth2TokenValidator<>(validator, new JwtClaimValidator<java.util.List<String>>(
                    "aud", aud -> aud != null && aud.contains(issuer.audience())));
        }
        decoder.setJwtValidator(validator);
        return new TrustedIssuer(issuer.kind(), (JwtDecoder) decoder);
    }
}
