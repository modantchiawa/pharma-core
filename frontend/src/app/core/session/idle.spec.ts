import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SESSION_POLICY,
  closedSessionNotice,
  idleDeadlines,
  idleDuration,
  idleMinutes,
  idleState,
  minutesBeforeLogout,
  pingDue,
  sanitizePolicy,
  sessionPolicyProblem,
  warningProgress,
} from './idle';

const MIN = 60_000;
const T0 = Date.UTC(2026, 9, 27, 8, 0, 0);
const policy = { lockAfterMinutes: 5, logoutAfterMinutes: 30 };

describe('idleDeadlines', () => {
  it('locks and closes counting from the last activity', () => {
    expect(idleDeadlines(T0, policy)).toEqual({ lockAt: T0 + 5 * MIN, logoutAt: T0 + 30 * MIN });
  });

  it('never locks with a lock delay of 0', () => {
    expect(idleDeadlines(T0, { lockAfterMinutes: 0, logoutAfterMinutes: 45 }).lockAt).toBeNull();
  });
});

describe('idleState', () => {
  it('stays active until 30 seconds before the lock', () => {
    expect(idleState(T0 + 4 * MIN, T0, policy, false).phase).toBe('active');
    expect(idleState(T0 + 5 * MIN - 30_001, T0, policy, false).phase).toBe('active');
  });

  it('warns with a countdown during the last 30 seconds', () => {
    expect(idleState(T0 + 5 * MIN - 27_400, T0, policy, false)).toEqual({
      phase: 'warning',
      next: 'lock',
      secondsLeft: 28,
      logoutDue: false,
    });
    expect(idleState(T0 + 5 * MIN - 1, T0, policy, false).secondsLeft).toBe(1);
  });

  it('locks at the delay and closes the session at the logout delay, locked or not', () => {
    expect(idleState(T0 + 5 * MIN, T0, policy, false)).toMatchObject({
      phase: 'locked',
      logoutDue: false,
    });
    expect(idleState(T0 + 29 * MIN, T0, policy, true).logoutDue).toBe(false);
    expect(idleState(T0 + 30 * MIN, T0, policy, true)).toMatchObject({
      phase: 'locked',
      logoutDue: true,
    });
  });

  it('warns before the logout when the screen never locks', () => {
    const never = { lockAfterMinutes: 0, logoutAfterMinutes: 10 };
    expect(idleState(T0 + 9 * MIN + 45_000, T0, never, false)).toEqual({
      phase: 'warning',
      next: 'logout',
      secondsLeft: 15,
      logoutDue: false,
    });
    expect(idleState(T0 + 10 * MIN, T0, never, false)).toMatchObject({
      phase: 'active',
      logoutDue: true,
    });
  });

  it('keeps a manually locked screen locked whatever the activity', () => {
    expect(idleState(T0 + 1_000, T0, policy, true).phase).toBe('locked');
  });
});

describe('sessionPolicyProblem', () => {
  it('accepts what the server accepts', () => {
    expect(sessionPolicyProblem(5, 30)).toBeNull();
    expect(sessionPolicyProblem(0, 5)).toBeNull();
    expect(sessionPolicyProblem(120, 720)).toBeNull();
  });

  it('refuses out of bounds or inconsistent delays with the server words', () => {
    expect(sessionPolicyProblem(121, 720)).toContain('120 minutes');
    expect(sessionPolicyProblem(-1, 30)).not.toBeNull();
    expect(sessionPolicyProblem(1.5, 30)).not.toBeNull();
    expect(sessionPolicyProblem(0, 4)).toContain('5 minutes');
    expect(sessionPolicyProblem(0, 721)).toContain('12 heures');
    expect(sessionPolicyProblem(30, 30)).toBe(
      'La déconnexion doit intervenir après le verrouillage',
    );
  });
});

describe('sanitizePolicy', () => {
  it('keeps a valid policy and falls back on the defaults otherwise', () => {
    expect(sanitizePolicy({ lockAfterMinutes: 10, logoutAfterMinutes: 60 })).toEqual({
      lockAfterMinutes: 10,
      logoutAfterMinutes: 60,
    });
    expect(sanitizePolicy(null)).toEqual(DEFAULT_SESSION_POLICY);
    expect(sanitizePolicy({ lockAfterMinutes: 50, logoutAfterMinutes: 20 })).toEqual(
      DEFAULT_SESSION_POLICY,
    );
    expect(sanitizePolicy({ lockAfterMinutes: '5' })).toEqual(DEFAULT_SESSION_POLICY);
  });
});

describe('countdowns', () => {
  it('counts idle minutes down and up', () => {
    expect(idleMinutes(T0 + 5 * MIN + 59_000, T0)).toBe(5);
    expect(minutesBeforeLogout(T0 + 5 * MIN, T0, policy)).toBe(25);
    expect(minutesBeforeLogout(T0 + 29 * MIN + 1_000, T0, policy)).toBe(1);
    expect(minutesBeforeLogout(T0 + 31 * MIN, T0, policy)).toBe(0);
  });

  it('tells the time without activity in seconds, then minutes', () => {
    expect(idleDuration(T0 + 30_400, T0)).toBe('30 s');
    expect(idleDuration(T0 + 4 * MIN + 30_000, T0)).toBe('4 min 30');
    expect(idleDuration(T0 + 4 * MIN + 5_000, T0)).toBe('4 min 05');
    expect(idleDuration(T0 + 10 * MIN, T0)).toBe('10 min');
  });

  it('empties the warning ring from full to nothing', () => {
    expect(warningProgress(30)).toBe(1);
    expect(warningProgress(15)).toBe(0.5);
    expect(warningProgress(0)).toBe(0);
    expect(warningProgress(null)).toBe(0);
  });
});

describe('closedSessionNotice', () => {
  it('tells why the session was closed', () => {
    expect(closedSessionNotice('idle', 30)).toBe('Session fermée après 30 min d’inactivité');
    expect(closedSessionNotice('pin', null)).toContain('5 codes erronés');
    expect(closedSessionNotice(null, null)).toBeNull();
  });
});

describe('pingDue', () => {
  it('reports activity to the server at most once a minute', () => {
    expect(pingDue(T0, 0, 0)).toBe(true);
    expect(pingDue(T0 + 59_999, T0, 0)).toBe(false);
    expect(pingDue(T0 + MIN, T0, 0)).toBe(true);
  });

  it('waits when a request the server counts as activity just went out', () => {
    expect(pingDue(T0 + 2 * MIN, T0, T0 + 90_000)).toBe(false);
    expect(pingDue(T0 + 3 * MIN, T0, T0 + 90_000)).toBe(true);
  });
});
