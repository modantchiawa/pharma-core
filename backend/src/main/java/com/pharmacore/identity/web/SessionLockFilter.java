package com.pharmacore.identity.web;

import com.pharmacore.identity.application.LoginSessionService;
import com.pharmacore.identity.application.SessionClosedException;
import com.pharmacore.identity.domain.LockedRequestPolicy;
import com.pharmacore.identity.domain.SessionLockRules.Status;
import com.pharmacore.shared.context.Actor;
import com.pharmacore.shared.security.PharmacyAuthenticationToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The server side of the terminal lock, on a hub and on the cloud alike: every request of a staff
 * member is checked against the state of its login session. Locked (by the terminal, or by the
 * server itself once the lock delay is past), the session gets <b>423</b> {@code session-locked}
 * on everything but its lock, its journal and its heartbeat; past the logout delay it is closed
 * and gets <b>401</b> {@code session-expired}. Requests not marked as background are the user's
 * activity. Platform operators, hubs and system processes have no such session.
 */
public class SessionLockFilter extends OncePerRequestFilter {

    private final LoginSessionService sessions;
    private final ObjectMapper json;

    public SessionLockFilter(LoginSessionService sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (LockedRequestPolicy.outsideSession(path)
                || !(SecurityContextHolder.getContext().getAuthentication() instanceof PharmacyAuthenticationToken token)
                || !(token.actor() instanceof Actor.TenantUser user)
                || !(token.getCredentials() instanceof Jwt jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();
        boolean allowedWhileLocked = LockedRequestPolicy.allowedWhileLocked(method, path);
        boolean issuedLocked = LoginSessionService.issuedLocked(jwt);
        Optional<UUID> session = sessions.sessionOf(jwt);
        if (session.isEmpty()) {
            if (sessions.withoutSession(jwt)) {
                // Signed here before the server-side sessions: a refresh brings a token bound to one
                problem(response, HttpStatus.UNAUTHORIZED, "token-outdated", "Session à renouveler",
                        "Jeton à renouveler");
            } else if (issuedLocked && !allowedWhileLocked) {
                refuseLocked(response);
            } else {
                chain.doFilter(request, response);
            }
            return;
        }
        boolean activity = !issuedLocked && LockedRequestPolicy.countsAsActivity(method, path,
                request.getHeader(LockedRequestPolicy.BACKGROUND_HEADER));
        Status status = sessions.check(session.get(), user.userId(), activity);
        switch (status) {
            case CLOSED -> problem(response, HttpStatus.UNAUTHORIZED, SessionClosedException.CODE, "Session fermée",
                    "Session fermée, reconnectez-vous");
            case LOCKED -> {
                if (allowedWhileLocked) {
                    chain.doFilter(request, response);
                } else {
                    refuseLocked(response);
                }
            }
            case ACTIVE -> {
                if (issuedLocked && !allowedWhileLocked) {
                    // Unlocked since (in another tab): this token predates it, a refresh brings a full one
                    problem(response, HttpStatus.UNAUTHORIZED, "token-outdated", "Session à renouveler",
                            "Jeton à renouveler");
                } else {
                    chain.doFilter(request, response);
                }
            }
        }
    }

    private void refuseLocked(HttpServletResponse response) throws IOException {
        problem(response, HttpStatus.LOCKED, "session-locked", "Session verrouillée",
                "Poste verrouillé : saisissez votre code PIN ou votre mot de passe pour continuer");
    }

    private void problem(HttpServletResponse response, HttpStatus status, String code, String title, String detail)
            throws IOException {
        var problem = new LinkedHashMap<String, Object>();
        problem.put("type", "https://pharmacore.io/problems/" + code);
        problem.put("title", title);
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("code", code);
        response.setStatus(status.value());
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        }
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
