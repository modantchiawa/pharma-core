package com.pharmacore.supervision.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Rules turning heartbeats into a synchronization verdict, shared by the vendor console and the
 * pharmacy's own "Serveur local" page.
 *
 * <p>A register posts a heartbeat every 60 s (pending sales, last replay); a hub runs a
 * synchronization round every 30 s and contacts the cloud at least once per round (pull), even
 * when it has nothing to push. In hub mode the registers report to the hub and the hub replicates
 * {@code terminal_heartbeat} to the cloud: the cloud's picture is the one it had at the hub's last
 * contact. Verdicts are therefore computed at a <em>reference instant</em>, the moment the data
 * was last known to be current, never against a clock the data could not have followed.
 */
public final class SyncSupervision {

    /** Five missed register heartbeats in a row: the register is considered off-line. */
    public static final Duration TERMINAL_STALE_AFTER = Duration.ofMinutes(5);

    /** Ten missed synchronization rounds: the hub no longer reaches the cloud. */
    public static final Duration HUB_UNREACHABLE_AFTER = Duration.ofMinutes(5);

    private SyncSupervision() {
    }

    /**
     * @param lastSeenAt   last heartbeat received
     * @param lastSyncAt   last successful replay of offline sales reported by the register, or
     *                     {@code null} when it never replayed any
     * @param pendingSales sales waiting in the register's local journal
     * @param reference    instant at which the heartbeat data is known to be current
     */
    public static TerminalSyncState terminalState(Instant lastSeenAt, Instant lastSyncAt, int pendingSales,
                                                  Instant reference) {
        if (isStale(lastSeenAt, reference)) {
            return TerminalSyncState.STALE;
        }
        if (pendingSales > 0) {
            return TerminalSyncState.PENDING;
        }
        if (lastSyncAt == null) {
            return TerminalSyncState.NEVER;
        }
        return TerminalSyncState.SYNCED;
    }

    /** Silent for strictly longer than {@link #TERMINAL_STALE_AFTER} at the reference instant. */
    public static boolean isStale(Instant lastSeenAt, Instant reference) {
        return lastSeenAt == null || Duration.between(lastSeenAt, reference).compareTo(TERMINAL_STALE_AFTER) > 0;
    }

    /**
     * Instant at which a pharmacy's register data is current.
     *
     * @param relayedByHub   the data reached this node through a hub's replication (cloud view of a
     *                       pharmacy in hub mode)
     * @param hubLastContact last time the hub contacted the cloud, {@code null} if never
     */
    public static Instant reference(boolean relayedByHub, Instant hubLastContact, Instant now) {
        if (!relayedByHub || hubLastContact == null || hubLastContact.isAfter(now)) {
            return now;
        }
        return hubLastContact;
    }

    /** The hub contacted the cloud within {@link #HUB_UNREACHABLE_AFTER}. */
    public static boolean hubReachable(Instant hubLastContact, Instant now) {
        return hubLastContact != null
                && Duration.between(hubLastContact, now).compareTo(HUB_UNREACHABLE_AFTER) <= 0;
    }
}
