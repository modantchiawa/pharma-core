package com.pharmacore.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What the server knows of a login session.
 *
 * @param lastActivityAt last request or activity ping of the user (background requests excluded)
 * @param lockedAt       null while unlocked
 * @param ended          closed (logout, inactivity, wrong codes) or without any valid refresh token left
 */
public record LoginSessionState(UUID userId, Instant lastActivityAt, Instant lockedAt, LockReason lockReason,
                                boolean ended) {

    public boolean locked() {
        return lockedAt != null;
    }

    public LoginSessionState withActivity(Instant at) {
        return at.isAfter(lastActivityAt) ? new LoginSessionState(userId, at, lockedAt, lockReason, ended) : this;
    }

    public LoginSessionState lockedAt(Instant at, LockReason reason) {
        return new LoginSessionState(userId, lastActivityAt, at, reason, ended);
    }

    public LoginSessionState unlocked(Instant at) {
        return new LoginSessionState(userId, at, null, null, ended);
    }

    public LoginSessionState closed() {
        return new LoginSessionState(userId, lastActivityAt, lockedAt, lockReason, true);
    }
}
