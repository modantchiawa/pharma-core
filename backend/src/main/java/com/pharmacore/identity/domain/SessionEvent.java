package com.pharmacore.identity.domain;

/** What a terminal reports when it locks or closes a session on its own (audit trail). */
public enum SessionEvent {
    /** No activity for the pharmacy's lock delay. */
    IDLE_LOCK("SESSION_LOCKED"),
    /** The user locked the screen (button or Ctrl+L). */
    MANUAL_LOCK("SESSION_LOCKED"),
    /** No activity for the pharmacy's logout delay. */
    IDLE_LOGOUT("SESSION_IDLE_LOGOUT"),
    /** Too many wrong PINs on the lock screen. */
    PIN_FAILURES_LOGOUT("SESSION_PIN_LOGOUT");

    private final String auditAction;

    SessionEvent(String auditAction) {
        this.auditAction = auditAction;
    }

    public String auditAction() {
        return auditAction;
    }
}
