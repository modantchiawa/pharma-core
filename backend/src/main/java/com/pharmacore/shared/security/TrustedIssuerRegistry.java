package com.pharmacore.shared.security;

import java.util.Optional;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Resolves a signature-verifying decoder for an issuer, only if that issuer is trusted. */
public interface TrustedIssuerRegistry {

    Optional<TrustedIssuer> find(String issuer);

    record TrustedIssuer(SecurityProperties.IssuerKind kind, JwtDecoder decoder) {
    }
}
