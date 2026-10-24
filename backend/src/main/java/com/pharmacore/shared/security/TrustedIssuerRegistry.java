package com.pharmacore.shared.security;

import java.util.Optional;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Resolves a signature-verifying decoder for an issuer (and key id), only if that issuer is trusted. */
public interface TrustedIssuerRegistry {

    Optional<TrustedIssuer> find(String issuer, String kid);

    record TrustedIssuer(SecurityProperties.IssuerKind kind, JwtDecoder decoder) {
    }
}
