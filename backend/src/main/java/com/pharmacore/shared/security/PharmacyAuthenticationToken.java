package com.pharmacore.shared.security;

import com.pharmacore.shared.context.Actor;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Authenticated request carrying the verified JWT and the resolved {@link Actor}. */
public class PharmacyAuthenticationToken extends AbstractAuthenticationToken {

    private final Jwt jwt;
    private final Actor actor;

    public PharmacyAuthenticationToken(Jwt jwt, Actor actor, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.jwt = jwt;
        this.actor = actor;
        setAuthenticated(true);
    }

    public Actor actor() {
        return actor;
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }

    @Override
    public String getName() {
        return actor.auditId();
    }
}
