package com.pharmacore.identity.domain;

import java.util.List;
import java.util.Set;

/**
 * What a locked session may still ask, and which requests are the user's activity. Locked, a
 * terminal only reports its own state (lock, session journal, heartbeat): nothing it receives
 * reveals pharmacy data and nothing it sends changes any. Authentication (unlock, sign-out,
 * user switch) and public pages carry no session and are never concerned.
 */
public final class LockedRequestPolicy {

    /** Marks a request the terminal sends on its own (polls, heartbeat, replay, live updates). */
    public static final String BACKGROUND_HEADER = "X-PharmaCore-Background";

    private static final List<String> OUTSIDE_SESSION = List.of("/api/auth/", "/api/public/");

    private static final Set<String> ALLOWED_WHILE_LOCKED = Set.of(
            "POST /api/v1/me/session-events",
            "POST /api/v1/me/session/lock",
            "POST /api/v1/pos/heartbeat");

    private LockedRequestPolicy() {
    }

    public static boolean outsideSession(String path) {
        return OUTSIDE_SESSION.stream().anyMatch(path::startsWith);
    }

    public static boolean allowedWhileLocked(String method, String path) {
        String normalized = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        return ALLOWED_WHILE_LOCKED.contains(method + " " + normalized);
    }

    /**
     * A request is activity unless the terminal marked it as background; the requests a locked
     * terminal may send (its lock, its journal, its heartbeat) never are, marked or not.
     */
    public static boolean countsAsActivity(String method, String path, String backgroundHeader) {
        boolean background = backgroundHeader != null && !backgroundHeader.isBlank();
        return !background && !allowedWhileLocked(method, path);
    }
}
