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
    public static final String HUB_NODE_AUTHORITY = "ROLE_HUB_NODE";

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
        var header = unverifiedHeader(token);
        var trusted = issuers.find(header.issuer(), header.kid())
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
            case Actor.HubNode _ -> authorities.add(new SimpleGrantedAuthority(HUB_NODE_AUTHORITY));
            case Actor.SystemProcess _ -> throw new InvalidBearerTokenException("System actors cannot hold tokens");
        }
        return authorities;
    }

    private static UnverifiedHeader unverifiedHeader(String token) {
        try {
            var jwt = JWTParser.parse(token);
            String kid = jwt.getHeader() instanceof com.nimbusds.jose.JWSHeader jws ? jws.getKeyID() : null;
            return new UnverifiedHeader(jwt.getJWTClaimsSet().getIssuer(), kid);
        } catch (ParseException e) {
            throw new InvalidBearerTokenException("Malformed token", e);
        }
    }

    private record UnverifiedHeader(String issuer, String kid) {
    }
}
