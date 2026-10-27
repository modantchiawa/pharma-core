import { LicenseStateView, LicenseStatus } from '../api/models';

const DAY_MS = 86_400_000;
/** A terminal that cannot reach its server stops trusting its cached state after this long. */
export const TERMINAL_OFFLINE_WINDOW_MS = 15 * DAY_MS;
/** Clock moved back further than this since the state was fetched: the clock is not trusted. */
export const CLOCK_TOLERANCE_MS = 10 * 60_000;

/** The server's answer as this terminal stored it (epoch milliseconds of the terminal clock). */
export interface CachedLicense {
  status: LicenseStatus;
  fetchedAt: number;
}

/**
 * State to enforce now on this terminal: the server's, moved forward with the terminal's clock
 * so that a terminal that lost its server still warns, enters the grace period and blocks on
 * the dates the server announced. {@code null} while nothing is known (never blocks).
 */
export function effectiveState(cached: CachedLicense | null, now: number): LicenseStateView | null {
  if (!cached) {
    return null;
  }
  const { state, paidUntil, graceUntil } = cached.status;
  if (state.status === 'BLOCKED') {
    return state;
  }
  if (now < cached.fetchedAt - CLOCK_TOLERANCE_MS) {
    return blocked('CLOCK_TAMPERED', null, state.blocksAt);
  }
  const blocksAt = instantOf(state.blocksAt);
  if (blocksAt !== null && now >= blocksAt) {
    const offline = state.blocksAt === cached.status.license.validUntil;
    return blocked(offline ? 'OFFLINE_TOO_LONG' : 'EXPIRED', state.blocksAt, state.blocksAt);
  }
  const offlineLimit = cached.fetchedAt + TERMINAL_OFFLINE_WINDOW_MS;
  if (now >= offlineLimit) {
    const since = new Date(offlineLimit).toISOString();
    return blocked('OFFLINE_TOO_LONG', since, since);
  }
  const paid = instantOf(paidUntil);
  if (paid !== null && now >= paid && state.status !== 'GRACE') {
    return {
      ...state,
      status: 'GRACE',
      reason: 'EXPIRED',
      deadline: graceUntil,
      daysLeft: daysLeft(graceUntil, now),
    };
  }
  return { ...state, daysLeft: state.deadline ? daysLeft(state.deadline, now) : state.daysLeft };
}

/** Whole days left, rounded up like the server: 6 days and 2 hours is « 7 jours ». */
export function daysLeft(deadline: string | null, now: number): number | null {
  const at = instantOf(deadline);
  if (at === null) {
    return null;
  }
  return Math.max(0, Math.ceil((at - now) / DAY_MS));
}

function blocked(
  reason: LicenseStateView['reason'],
  since: string | null,
  blocksAt: string | null,
): LicenseStateView {
  return {
    status: 'BLOCKED',
    reason,
    deadline: since,
    daysLeft: 0,
    blocksAt,
    blockedSince: since,
  };
}

/** Epoch milliseconds of an ISO instant, {@code null} when absent or unreadable. */
export function instantOf(iso: string | null): number | null {
  if (!iso) {
    return null;
  }
  const value = Date.parse(iso);
  return Number.isNaN(value) ? null : value;
}
