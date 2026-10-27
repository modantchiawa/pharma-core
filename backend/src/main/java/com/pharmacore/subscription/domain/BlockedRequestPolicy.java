package com.pharmacore.subscription.domain;

import java.util.List;
import java.util.Set;

/**
 * What a blocked pharmacy may still do. Reading is never blocked (stock, sales history,
 * prescription register, reports and exports are all GET); among the modifications only those
 * that keep the pharmacy able to regularize are allowed: its own password and PIN, the license
 * refresh and the synchronization, the terminal heartbeat, reprinting a ticket. Offline sales
 * replayed by a terminal reach the server, which keeps those made before the block.
 */
public final class BlockedRequestPolicy {

    /** Authentication, public pages, synchronization and the vendor console live outside it. */
    private static final String PHARMACY_API = "/api/v1/";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final List<String> ALLOWED_PATHS = List.of(
            "/api/v1/me/password",
            "/api/v1/me/pin",
            "/api/v1/subscription/refresh",
            "/api/v1/hub/sync/run",
            "/api/v1/pos/heartbeat",
            "/api/v1/pos/sync/sales",
            "/api/v1/print/tickets");

    private static final List<String> ALLOWED_PREFIXES = List.of("/api/v1/print/receipts/");

    private BlockedRequestPolicy() {
    }

    public static boolean allowedWhileBlocked(String method, String path) {
        if (READ_METHODS.contains(method) || !path.startsWith(PHARMACY_API)) {
            return true;
        }
        String normalized = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        return ALLOWED_PATHS.contains(normalized) || ALLOWED_PREFIXES.stream().anyMatch(normalized::startsWith);
    }
}
