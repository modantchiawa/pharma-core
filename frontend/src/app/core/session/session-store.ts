import { SessionPolicy } from '../api/models';
import { sanitizePolicy } from './idle';

/**
 * Session state shared by the tabs of this terminal (localStorage): last activity, lock and
 * the pharmacy's delays. A reload keeps a locked screen locked.
 */
const LAST_ACTIVITY_KEY = 'pharmacore.lastActivity';
const LOCK_KEY = 'pharmacore.locked';
const POLICY_KEY = 'pharmacore.sessionPolicy';
const CURRENT_USER_KEY = 'pharmacore.currentUser';

export interface LockRecord {
  since: number;
  manual: boolean;
}

export interface CurrentUser {
  userId: string;
  email: string;
}

export const SESSION_KEYS = { lastActivity: LAST_ACTIVITY_KEY, lock: LOCK_KEY } as const;

export function readLastActivity(): number | null {
  const value = Number(read(LAST_ACTIVITY_KEY));
  return Number.isFinite(value) && value > 0 ? value : null;
}

export function writeLastActivity(at: number): void {
  write(LAST_ACTIVITY_KEY, String(at));
}

export function readLock(): LockRecord | null {
  return parse<LockRecord>(read(LOCK_KEY));
}

export function writeLock(lock: LockRecord | null): void {
  write(LOCK_KEY, lock ? JSON.stringify(lock) : null);
}

export function readPolicy(): SessionPolicy {
  return sanitizePolicy(parse(read(POLICY_KEY)));
}

export function writePolicy(policy: SessionPolicy): void {
  write(POLICY_KEY, JSON.stringify(policy));
}

export function readCurrentUser(): CurrentUser | null {
  return parse<CurrentUser>(read(CURRENT_USER_KEY));
}

/**
 * A user just signed in on this terminal (password or PIN): the inactivity count starts now and
 * any lock left by the previous session is lifted.
 */
export function sessionStarted(user: CurrentUser): void {
  write(CURRENT_USER_KEY, JSON.stringify(user));
  writeLastActivity(Date.now());
  writeLock(null);
}

export function sessionEnded(): void {
  write(CURRENT_USER_KEY, null);
  write(LAST_ACTIVITY_KEY, null);
  writeLock(null);
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) {
      localStorage.removeItem(key);
    } else {
      localStorage.setItem(key, value);
    }
  } catch {
    // Storage disabled or full: the state lives in this tab only
  }
}

function parse<T>(raw: string | null): T | null {
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}
