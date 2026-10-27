package com.pharmacore.subscription.web;

import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.context.TenantContext;
import com.pharmacore.subscription.application.LicenseStateService;
import com.pharmacore.subscription.domain.BlockedRequestPolicy;
import com.pharmacore.subscription.domain.LicenseState;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The server-side guard of the subscription, on the cloud and on a hub alike: while a pharmacy
 * is BLOCKED, its staff's modifications are refused with 423 and a French problem detail; reads
 * and the few requests that let it regularize go through. Platform operators, hubs and system
 * processes are never blocked.
 */
public class LicenseEnforcementFilter extends OncePerRequestFilter {

    private final LicenseStateService states;
    private final ObjectMapper json;

    public LicenseEnforcementFilter(LicenseStateService states, ObjectMapper json) {
        this.states = states;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!BlockedRequestPolicy.allowedWhileBlocked(request.getMethod(), request.getRequestURI())
                && TenantContext.current().map(c -> c.actor()).orElse(null) instanceof Actor.TenantUser user) {
            LicenseState state = states.current(user.tenant());
            if (state.blocked()) {
                refuse(response, state);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletResponse response, LicenseState state) throws IOException {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("type", "https://pharmacore.io/problems/license-blocked");
        problem.put("title", "Abonnement bloqué");
        problem.put("status", HttpStatus.LOCKED.value());
        problem.put("detail", state.blockedMessage());
        problem.put("code", "license-blocked");
        problem.put("reason", state.reason().name());
        response.setStatus(HttpStatus.LOCKED.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
