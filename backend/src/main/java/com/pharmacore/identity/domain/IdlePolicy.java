package com.pharmacore.identity.domain;

import java.util.Optional;

/**
 * How long an unattended terminal stays open: after {@code lockAfterMinutes} without activity
 * the screen is locked (data hidden, page kept, unlocked by PIN or password), after
 * {@code logoutAfterMinutes} the session is closed. A lock delay of 0 never locks.
 */
public record IdlePolicy(int lockAfterMinutes, int logoutAfterMinutes) {

    public static final int DEFAULT_LOCK_MINUTES = 5;
    public static final int DEFAULT_LOGOUT_MINUTES = 30;
    public static final int MAX_LOCK_MINUTES = 120;
    public static final int MIN_LOGOUT_MINUTES = 5;
    public static final int MAX_LOGOUT_MINUTES = 720;
    public static final IdlePolicy DEFAULT = new IdlePolicy(DEFAULT_LOCK_MINUTES, DEFAULT_LOGOUT_MINUTES);

    /** The pharmacy's stored delays; a delay never set (NULL column) takes its default. */
    public static IdlePolicy stored(Integer lockAfterMinutes, Integer logoutAfterMinutes) {
        return new IdlePolicy(lockAfterMinutes == null ? DEFAULT_LOCK_MINUTES : lockAfterMinutes,
                logoutAfterMinutes == null ? DEFAULT_LOGOUT_MINUTES : logoutAfterMinutes);
    }

    /** Why these delays cannot be saved, in words the pharmacist reads; empty when they can. */
    public static Optional<String> problem(int lockAfterMinutes, int logoutAfterMinutes) {
        if (lockAfterMinutes < 0 || lockAfterMinutes > MAX_LOCK_MINUTES) {
            return Optional.of("Le verrouillage doit intervenir entre 1 et " + MAX_LOCK_MINUTES
                    + " minutes (0 pour ne jamais verrouiller)");
        }
        if (logoutAfterMinutes < MIN_LOGOUT_MINUTES || logoutAfterMinutes > MAX_LOGOUT_MINUTES) {
            return Optional.of("La déconnexion doit intervenir entre " + MIN_LOGOUT_MINUTES + " minutes et "
                    + MAX_LOGOUT_MINUTES / 60 + " heures");
        }
        if (lockAfterMinutes > 0 && logoutAfterMinutes <= lockAfterMinutes) {
            return Optional.of("La déconnexion doit intervenir après le verrouillage");
        }
        return Optional.empty();
    }
}
