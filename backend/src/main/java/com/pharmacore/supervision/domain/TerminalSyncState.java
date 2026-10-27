package com.pharmacore.supervision.domain;

/**
 * Synchronization state of a cash register, derived from its last heartbeat by
 * {@link SyncSupervision#terminalState}. Listed from the most to the least urgent.
 */
public enum TerminalSyncState {
    /** No heartbeat for longer than {@link SyncSupervision#TERMINAL_STALE_AFTER}: the register is off or cut off. */
    STALE,
    /** In contact, but sales recorded offline still wait in its local journal. */
    PENDING,
    /** In contact, nothing waiting, but it never replayed a single sale (new or back-office station). */
    NEVER,
    /** In contact and its journal is empty: everything it sold is on the server. */
    SYNCED
}
