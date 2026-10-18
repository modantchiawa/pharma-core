package com.pharmacore.shared.security;

import com.nimbusds.jwt.JWTParser;
import com.pharmacore.shared.context.Actor;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Multi-issuer bearer token authentication shared by HTTP and STOMP: the (unverified) issuer
 * selects a trusted decoder, which then verifies signature, expiry, issuer and audience.
 */
@Component
public class BearerTokenAuthenticator implements AuthenticationManager {

    public static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_PLATFORM_ADMIN";

    private final TrustedIssuerRegistry issuers;
    private final ActorResolver actors;

    public BearerTokenAuthenticator(TrustedIssuerRegistry issuers, ActorResolver actors) {
        this.issuers = issuers;
        this.actors = actors;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) {
            return null;
        }
        return authenticate(bearer.getToken());
    }

    public PharmacyAuthenticationToken authenticate(String token) {
        var trusted = issuers.find(unverifiedIssuer(token))
                .orElseThrow(() -> new InvalidBearerTokenException("Untrusted token issuer"));
        Jwt jwt;
        try {
            jwt = trusted.decoder().decode(token);
        } catch (JwtException e) {
            throw new InvalidBearerTokenException(e.getMessage(), e);
        }
        Actor actor = actors.resolve(jwt, trusted.kind());
        return new PharmacyAuthenticationToken(jwt, actor, authorities(actor));
    }

    static List<GrantedAuthority> authorities(Actor actor) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        switch (actor) {
            case Actor.TenantUser user -> {
                user.permissions().forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
                user.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            case Actor.PlatformOperator _ -> authorities.add(new SimpleGrantedAuthority(PLATFORM_ADMIN_AUTHORITY));
            case Actor.SystemProcess _ -> throw new InvalidBearerTokenException("System actors cannot hold tokens");
        }
        return authorities;
    }

    private static String unverifiedIssuer(String token) {
        try {
            return JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new InvalidBearerTokenException("Malformed token", e);
        }
    }
}
