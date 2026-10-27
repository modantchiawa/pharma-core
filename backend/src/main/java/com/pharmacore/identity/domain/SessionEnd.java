package com.pharmacore.identity.domain;

/** Why a login session was closed. */
public enum SessionEnd {
    /** The user signed out, or another user switched in on the locked terminal. */
    LOGOUT,
    /** No activity for the pharmacy's logout delay. */
    IDLE_LOGOUT,
    /** The fifth wrong PIN or password on the lock screen. */
    UNLOCK_FAILURES
}
