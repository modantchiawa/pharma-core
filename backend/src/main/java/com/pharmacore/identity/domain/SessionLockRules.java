package com.pharmacore.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * When the server considers a login session locked or closed. The terminal locks on time by
 * itself and tells the server at once; these deadlines are the server's own, applied whatever
 * the terminal does (page modified, request blocked, clock changed). They allow for what the
 * server cannot see yet: the terminal reports activity without API calls at most once a minute
 * ({@link #ACTIVITY_PING_INTERVAL}) and an instance writes it at most every
 * {@link #PERSIST_INTERVAL}, so a user still working is never locked out early.
 */
public final class SessionLockRules {

    public static final Duration ACTIVITY_PING_INTERVAL = Duration.ofSeconds(60);
    public static final Duration PERSIST_INTERVAL = Duration.ofSeconds(30);
    public static final Duration GRACE = ACTIVITY_PING_INTERVAL.plus(PERSIST_INTERVAL);

    public enum Status {
        ACTIVE,
        LOCKED,
        CLOSED
    }

    /**
     * @param changed  the session has just reached this status (to record and audit)
     * @param lockedAt when the lock started, for a locked session
     */
    public record Verdict(Status status, boolean changed, Instant lockedAt) {
    }

    private SessionLockRules() {
    }

    public static Verdict evaluate(LoginSessionState state, IdlePolicy policy, Instant now) {
        if (state == null || state.ended()) {
            return new Verdict(Status.CLOSED, false, null);
        }
        if (!now.isBefore(logoutAt(state, policy))) {
            return new Verdict(Status.CLOSED, true, null);
        }
        if (state.locked()) {
            return new Verdict(Status.LOCKED, false, state.lockedAt());
        }
        Instant lockAt = lockAt(state, policy);
        if (lockAt != null && !now.isBefore(lockAt.plus(GRACE))) {
            return new Verdict(Status.LOCKED, true, lockAt);
        }
        return new Verdict(Status.ACTIVE, false, null);
    }

    /** End of the lock delay counted from the last activity; null when the pharmacy never locks. */
    public static Instant lockAt(LoginSessionState state, IdlePolicy policy) {
        return policy.lockAfterMinutes() == 0 ? null
                : state.lastActivityAt().plus(Duration.ofMinutes(policy.lockAfterMinutes()));
    }

    /** The session closes after the logout delay without activity, locked or not. */
    public static Instant logoutAt(LoginSessionState state, IdlePolicy policy) {
        return state.lastActivityAt().plus(Duration.ofMinutes(policy.logoutAfterMinutes())).plus(GRACE);
    }

    /** Whether activity seen in memory must now be written (at most once per interval). */
    public static boolean persistDue(Instant persistedActivity, Instant observedActivity) {
        return observedActivity != null
                && !observedActivity.isBefore(persistedActivity.plus(PERSIST_INTERVAL));
    }

    /** Whole minutes without activity, as journaled. */
    public static int idleMinutes(LoginSessionState state, Instant now) {
        return (int) Math.max(0, Duration.between(state.lastActivityAt(), now).toMinutes());
    }
}
