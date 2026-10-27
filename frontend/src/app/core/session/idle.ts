import { SessionPolicy } from '../api/models';

/** Server defaults (IdlePolicy.DEFAULT): lock after 5 minutes, close the session after 30. */
export const DEFAULT_SESSION_POLICY: SessionPolicy = {
  lockAfterMinutes: 5,
  logoutAfterMinutes: 30,
};
/** Server bounds (IdlePolicy), in minutes. */
export const IDLE_LIMITS = { maxLock: 120, minLogout: 5, maxLogout: 720 } as const;
/** The warning shows this long before the screen locks (or the session closes). */
export const WARNING_MS = 30_000;
/** Wrong PINs on the lock screen before the session is closed. */
export const MAX_UNLOCK_FAILURES = 5;
/**
 * The user's activity reaches the server at least this often while they work without calling the
 * API (typing, scrolling): the server's own deadlines allow for it (SessionLockRules.GRACE).
 */
export const ACTIVITY_PING_MS = 60_000;

const MINUTE_MS = 60_000;

export type IdlePhase = 'active' | 'warning' | 'locked';

export interface IdleState {
  phase: IdlePhase;
  /** What the warning announces. */
  next: 'lock' | 'logout' | null;
  /** Whole seconds left before it, while the warning shows. */
  secondsLeft: number | null;
  /** The logout delay has passed: the session must be closed now. */
  logoutDue: boolean;
}

/** Why these delays cannot be saved (same rules and words as the server); null when they can. */
export function sessionPolicyProblem(
  lockAfterMinutes: number,
  logoutAfterMinutes: number,
): string | null {
  if (
    !Number.isInteger(lockAfterMinutes) ||
    lockAfterMinutes < 0 ||
    lockAfterMinutes > IDLE_LIMITS.maxLock
  ) {
    return `Le verrouillage doit intervenir entre 1 et ${IDLE_LIMITS.maxLock} minutes (0 pour ne jamais verrouiller)`;
  }
  if (
    !Number.isInteger(logoutAfterMinutes) ||
    logoutAfterMinutes < IDLE_LIMITS.minLogout ||
    logoutAfterMinutes > IDLE_LIMITS.maxLogout
  ) {
    return `La déconnexion doit intervenir entre ${IDLE_LIMITS.minLogout} minutes et ${IDLE_LIMITS.maxLogout / 60} heures`;
  }
  if (lockAfterMinutes > 0 && logoutAfterMinutes <= lockAfterMinutes) {
    return 'La déconnexion doit intervenir après le verrouillage';
  }
  return null;
}

/** A cached or received policy, or the defaults when it is missing or not valid. */
export function sanitizePolicy(value: unknown): SessionPolicy {
  const candidate = value as Partial<SessionPolicy> | null | undefined;
  const lock = candidate?.lockAfterMinutes;
  const logout = candidate?.logoutAfterMinutes;
  return typeof lock === 'number' &&
    typeof logout === 'number' &&
    sessionPolicyProblem(lock, logout) === null
    ? { lockAfterMinutes: lock, logoutAfterMinutes: logout }
    : DEFAULT_SESSION_POLICY;
}

/** Instants (epoch ms) at which the screen locks (null: never) and the session closes. */
export function idleDeadlines(
  lastActivity: number,
  policy: SessionPolicy,
): { lockAt: number | null; logoutAt: number } {
  return {
    lockAt: policy.lockAfterMinutes > 0 ? lastActivity + policy.lockAfterMinutes * MINUTE_MS : null,
    logoutAt: lastActivity + policy.logoutAfterMinutes * MINUTE_MS,
  };
}

/**
 * Where an idle terminal stands at `now`. The logout counts from the last activity of the
 * signed-in user, lock screen included: moving the mouse over a locked screen keeps nobody in.
 */
export function idleState(
  now: number,
  lastActivity: number,
  policy: SessionPolicy,
  locked: boolean,
): IdleState {
  const { lockAt, logoutAt } = idleDeadlines(lastActivity, policy);
  const logoutDue = now >= logoutAt;
  if (locked || (lockAt !== null && now >= lockAt)) {
    return { phase: 'locked', next: null, secondsLeft: null, logoutDue };
  }
  const nextAt = lockAt ?? logoutAt;
  if (!logoutDue && now >= nextAt - WARNING_MS) {
    return {
      phase: 'warning',
      next: lockAt !== null ? 'lock' : 'logout',
      secondsLeft: Math.max(1, Math.ceil((nextAt - now) / 1000)),
      logoutDue,
    };
  }
  return { phase: 'active', next: null, secondsLeft: null, logoutDue };
}

/** Whole minutes without activity, as journaled and shown to the user. */
export function idleMinutes(now: number, lastActivity: number): number {
  return Math.max(0, Math.floor((now - lastActivity) / MINUTE_MS));
}

/** Time without activity as the warning tells it: "30 s", "4 min 30", "10 min". */
export function idleDuration(now: number, lastActivity: number): string {
  const seconds = Math.max(0, Math.floor((now - lastActivity) / 1000));
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const rest = seconds % 60;
  return rest === 0
    ? `${seconds / 60} min`
    : `${Math.floor(seconds / 60)} min ${String(rest).padStart(2, '0')}`;
}

/** Minutes left before the locked session closes, rounded up ("dans 1 min" until the end). */
export function minutesBeforeLogout(
  now: number,
  lastActivity: number,
  policy: SessionPolicy,
): number {
  const { logoutAt } = idleDeadlines(lastActivity, policy);
  return Math.max(0, Math.ceil((logoutAt - now) / MINUTE_MS));
}

/** Share of the warning still to run, from 1 (just shown) to 0 (time is up), for the ring. */
export function warningProgress(secondsLeft: number | null): number {
  if (secondsLeft === null) {
    return 0;
  }
  return Math.min(1, Math.max(0, secondsLeft / (WARNING_MS / 1000)));
}

/** Message of the sign-in screen after the session was closed on its own. */
export function closedSessionNotice(reason: string | null, minutes: number | null): string | null {
  if (reason === 'idle') {
    return minutes && minutes > 0
      ? `Session fermée après ${minutes} min d’inactivité`
      : 'Session fermée après une période d’inactivité';
  }
  if (reason === 'pin') {
    return `Session fermée après ${MAX_UNLOCK_FAILURES} codes erronés sur l’écran verrouillé`;
  }
  return null;
}

/**
 * Whether the user's activity must be reported to the server now: at most once a minute, and
 * not when a request the server counts as activity went out within that minute.
 */
export function pingDue(now: number, lastPing: number, lastActivityRequest: number): boolean {
  return now - Math.max(lastPing, lastActivityRequest) >= ACTIVITY_PING_MS;
}
