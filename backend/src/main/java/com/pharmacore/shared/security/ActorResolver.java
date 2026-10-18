package com.pharmacore.shared.security;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantId;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Maps a verified JWT to an {@link Actor}. Pharmacy roles are customizable per tenant, so they
 * are resolved from the database (cached for a short time) rather than trusted from the token.
 */
@Component
public class ActorResolver {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String PLATFORM_ROLES_CLAIM = "platform_roles";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final TenantUserLoader loader;

    public ActorResolver(TenantUserLoader loader) {
        this.loader = loader;
    }

    public Actor resolve(Jwt jwt, SecurityProperties.IssuerKind kind) {
        return switch (kind) {
            case PLATFORM -> platformOperator(jwt);
            case TENANT -> tenantUser(jwt);
        };
    }

    private Actor platformOperator(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(PLATFORM_ROLES_CLAIM);
        if (roles == null || !roles.contains(SUPER_ADMIN)) {
            throw new InvalidBearerTokenException("Platform token without " + SUPER_ADMIN + " role");
        }
        String name = jwt.getClaimAsString("name");
        return new Actor.PlatformOperator(jwt.getSubject(), name == null ? jwt.getSubject() : name);
    }

    private Actor tenantUser(Jwt jwt) {
        String claim = jwt.getClaimAsString(TENANT_CLAIM);
        if (claim == null) {
            throw new InvalidBearerTokenException("Missing " + TENANT_CLAIM + " claim");
        }
        TenantId tenant;
        try {
            tenant = TenantId.of(claim);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Malformed " + TENANT_CLAIM + " claim");
        }
        return loader.load(tenant, jwt.getSubject());
    }

}
