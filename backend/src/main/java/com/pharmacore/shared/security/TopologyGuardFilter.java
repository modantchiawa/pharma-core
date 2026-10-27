package com.pharmacore.shared.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.shared.runtime.NodeProperties;
import com.pharmacore.tenant.persistence.TenantTopology;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On the cloud, a pharmacy served by a hub must not write operational data through the cloud
 * (two masters would conflict): those requests are refused with a pointer to the hub. Network
 * features (stock lookup, transfers, chat, duty roster) stay available from the cloud.
 */
public class TopologyGuardFilter extends OncePerRequestFilter {

    private static final List<String> HUB_MANAGED_PREFIXES = List.of(
            "/api/v1/sales", "/api/v1/pos", "/api/v1/inventory", "/api/v1/cash", "/api/v1/customers",
            "/api/v1/procurement", "/api/v1/accounting", "/api/v1/users", "/api/v1/roles",
            "/api/v1/pharmacy", "/api/v1/hub", "/api/v1/control");

    private final NodeProperties node;
    private final JdbcClient jdbc;
    private final Cache<UUID, TenantTopology> topologies = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(100_000).build();

    public TopologyGuardFilter(NodeProperties node, JdbcClient jdbc) {
        this.node = node;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!node.isHub() && isHubManagedPath(request.getRequestURI()) && !"GET".equals(request.getMethod())
                && TenantContext.current().map(c -> c.actor()).orElse(null) instanceof Actor.TenantUser user
                && topology(user.tenant().value()) == TenantTopology.HUB) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("application/problem+json");
            response.getWriter().write("""
                    {"type":"https://pharmacore.io/problems/hub-managed","title":"Conflict","status":409,
                     "detail":"Cette pharmacie est gérée par son serveur local : utilisez l'application sur le réseau de l'officine.",
                     "code":"hub-managed"}""");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isHubManagedPath(String uri) {
        return HUB_MANAGED_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    private TenantTopology topology(UUID tenant) {
        return topologies.get(tenant, id -> jdbc.sql("SELECT topology FROM tenant WHERE id = :id")
                .param("id", id).query(String.class).optional().map(TenantTopology::valueOf).orElse(TenantTopology.CLOUD));
    }
}
