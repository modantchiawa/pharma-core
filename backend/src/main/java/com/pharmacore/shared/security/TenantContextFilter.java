package com.pharmacore.shared.security;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.RequestContext;
import com.pharmacore.shared.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the authenticated {@link Actor} and a correlation id to a {@link ScopedValue} for the
 * whole request, and mirrors them into the MDC so every JSON log line is enriched.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_CORRELATION = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationId(request);
        response.setHeader(CORRELATION_HEADER, correlationId);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof PharmacyAuthenticationToken token)) {
            withMdc(correlationId, null, () -> chain.doFilter(request, response));
            return;
        }
        var context = new RequestContext(correlationId, token.actor());
        try {
            TenantContext.callChecked(context, () -> {
                withMdc(correlationId, token.actor(), () -> chain.doFilter(request, response));
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static void withMdc(String correlationId, Actor actor, FilterAction action)
            throws IOException, ServletException {
        MDC.put("correlation_id", correlationId);
        if (actor != null) {
            actor.tenantId().ifPresent(tenant -> MDC.put("tenant_id", tenant.toString()));
            MDC.put("actor", actor.auditType() + ":" + actor.auditId());
        }
        try {
            action.run();
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("tenant_id");
            MDC.remove("actor");
        }
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(CORRELATION_HEADER);
        return header != null && SAFE_CORRELATION.matcher(header).matches() ? header : UUID.randomUUID().toString();
    }

    @FunctionalInterface
    private interface FilterAction {
        void run() throws IOException, ServletException;
    }
}
