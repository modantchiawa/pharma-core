package com.pharmacore.identity.domain;

/** Why a login session was locked; the audit tells who decided it. */
public enum LockReason {
    /** The user locked the terminal (padlock, Ctrl+L). */
    MANUAL,
    /** The terminal reached the pharmacy's lock delay and reported it. */
    IDLE,
    /** The server found the session past its lock delay before the terminal said anything. */
    SERVER
}
