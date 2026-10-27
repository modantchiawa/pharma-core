package com.pharmacore.identity.application;

import com.pharmacore.identity.domain.SessionEnd;

/** The login session is over: the terminal must sign in again (HTTP 401, {@code session-expired}). */
public class SessionClosedException extends RuntimeException {

    public static final String CODE = "session-expired";

    private final String reason;

    private SessionClosedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static SessionClosedException idle() {
        return new SessionClosedException(SessionEnd.IDLE_LOGOUT.name(),
                "Session fermée après une période d'inactivité");
    }

    public static SessionClosedException unlockFailures(int attempts) {
        return new SessionClosedException(SessionEnd.UNLOCK_FAILURES.name(),
                "Session fermée après " + attempts + " codes erronés sur l'écran verrouillé");
    }

    public static SessionClosedException expired() {
        return new SessionClosedException("EXPIRED", "Session expirée, reconnectez-vous");
    }

    /** IDLE_LOGOUT, UNLOCK_FAILURES or EXPIRED: what the sign-in screen tells the user. */
    public String reason() {
        return reason;
    }
}
