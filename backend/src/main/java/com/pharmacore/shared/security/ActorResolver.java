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
    public static final String TYPE_CLAIM = "typ";
    public static final String TYPE_USER = "user";
    public static final String TYPE_HUB_NODE = "hub-node";
    /** Platform operator token issued by the node itself (demonstration mode). */
    public static final String TYPE_PLATFORM = "platform";
    public static final String NODE_CLAIM = "node_id";

    private final TenantUserLoader loader;

    public ActorResolver(TenantUserLoader loader) {
        this.loader = loader;
    }

    public Actor resolve(Jwt jwt, SecurityProperties.IssuerKind kind) {
        return switch (kind) {
            case PLATFORM -> platformOperator(jwt);
            case TENANT -> tenantUser(jwt);
            case INTERNAL -> switch (String.valueOf(jwt.getClaimAsString(TYPE_CLAIM))) {
                case TYPE_HUB_NODE -> hubNode(jwt);
                case TYPE_PLATFORM -> platformOperator(jwt);
                default -> tenantUser(jwt);
            };
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

    private Actor hubNode(Jwt jwt) {
        String nodeId = jwt.getClaimAsString(NODE_CLAIM);
        if (nodeId == null || nodeId.isBlank()) {
            throw new InvalidBearerTokenException("Hub token without node id");
        }
        return new Actor.HubNode(tenant(jwt), nodeId);
    }

    private Actor tenantUser(Jwt jwt) {
        return loader.load(tenant(jwt), jwt.getSubject());
    }

    private static TenantId tenant(Jwt jwt) {
        String claim = jwt.getClaimAsString(TENANT_CLAIM);
        if (claim == null) {
            throw new InvalidBearerTokenException("Missing " + TENANT_CLAIM + " claim");
        }
        try {
            return TenantId.of(claim);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Malformed " + TENANT_CLAIM + " claim");
        }
    }

}
