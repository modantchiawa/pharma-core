import {
  HubView,
  PharmacySyncHealth,
  TerminalBoard,
  TerminalSyncState,
  TerminalView,
} from '../core/api/models';
import { IconName } from './ui/icons';

export type SyncTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

export interface SyncBadge {
  label: string;
  tone: SyncTone;
  icon: IconName;
}

const SECOND = 1_000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Below this, a date reads "à l'instant" rather than "il y a 0 min". */
const JUST_NOW_MS = 45 * SECOND;

/** States in the order the screens filter and count them. */
export const SYNC_STATES: readonly { state: TerminalSyncState; label: string }[] = [
  { state: 'PENDING', label: 'En attente' },
  { state: 'STALE', label: 'Hors ligne' },
  { state: 'NEVER', label: 'Jamais synchronisés' },
  { state: 'SYNCED', label: 'Synchronisés' },
];

function timeOf(at: string | Date | null | undefined): number | null {
  if (!at) {
    return null;
  }
  const time = typeof at === 'string' ? Date.parse(at) : at.getTime();
  return Number.isNaN(time) ? null : time;
}

export function plural(count: number, singular: string, pluralForm: string): string {
  return `${count} ${Math.abs(count) >= 2 ? pluralForm : singular}`;
}

/** Compact elapsed time, rounded down, never below one minute: "5 min", "2 h", "3 j". */
export function compactDuration(ms: number): string {
  const elapsed = Math.max(ms, 0);
  if (elapsed < HOUR) {
    return `${Math.max(1, Math.floor(elapsed / MINUTE))} min`;
  }
  if (elapsed < DAY) {
    return `${Math.floor(elapsed / HOUR)} h`;
  }
  return `${Math.floor(elapsed / DAY)} j`;
}

/** "à l'instant", "il y a 5 min", "il y a 2 h", "il y a 3 j"; "jamais" without a date. */
export function relativeTime(at: string | Date | null | undefined, now: number): string {
  const time = timeOf(at);
  if (time === null) {
    return 'jamais';
  }
  const elapsed = now - time;
  return elapsed < JUST_NOW_MS ? "à l'instant" : `il y a ${compactDuration(elapsed)}`;
}

/**
 * The register's state as a badge. The silence of an off-line register is measured at the
 * instant the data was current (`observedAt`), the same instant the server classified it at.
 */
export function terminalBadge(terminal: TerminalView, observedAt: string): SyncBadge {
  switch (terminal.state) {
    case 'SYNCED':
      return { label: 'Synchronisé', tone: 'success', icon: 'checkCircle' };
    case 'PENDING':
      return {
        label: plural(terminal.pendingSales, 'vente en attente', 'ventes en attente'),
        tone: 'warning',
        icon: 'clock',
      };
    case 'STALE': {
      const silence = (timeOf(observedAt) ?? 0) - (timeOf(terminal.lastSeenAt) ?? 0);
      return {
        label: `Hors ligne depuis ${compactDuration(silence)}`,
        tone: 'danger',
        icon: 'wifiOff',
      };
    }
    case 'NEVER':
      return { label: 'Jamais synchronisé', tone: 'info', icon: 'info' };
  }
}

/** Link between the pharmacy's local server and the cloud. */
export function hubBadge(hub: HubView | null, now: number): SyncBadge {
  if (!hub) {
    return { label: 'Pas de serveur local', tone: 'neutral', icon: 'globe' };
  }
  if (hub.reachable) {
    return { label: 'En liaison avec le cloud', tone: 'success', icon: 'wifi' };
  }
  const contact = timeOf(hub.lastContactAt);
  return contact === null
    ? { label: 'Jamais connecté au cloud', tone: 'danger', icon: 'cloudOff' }
    : {
        label: `Injoignable depuis ${compactDuration(now - contact)}`,
        tone: 'danger',
        icon: 'cloudOff',
      };
}

/** Freshness of a board: live, or as last relayed to the cloud by the pharmacy's hub. */
export function observedLabel(board: TerminalBoard, now: number): string {
  return board.relayedByHub
    ? `Vu par le cloud ${relativeTime(board.observedAt, now)}`
    : `Actualisé ${relativeTime(board.observedAt, now)}`;
}

/** Off-line registers holding no sale: the ones "Oublier les postes hors ligne" removes. */
export function forgettable(board: TerminalBoard): TerminalView[] {
  return board.terminals.filter((t) => t.state === 'STALE' && t.pendingSales === 0);
}

/** One-glance verdict for a pharmacy row of the console list. */
export function pharmacyBadges(health: PharmacySyncHealth | undefined): SyncBadge[] {
  if (!health || health.terminals === 0) {
    return [{ label: 'Aucun poste', tone: 'neutral', icon: 'monitor' }];
  }
  const badges: SyncBadge[] = [];
  if (health.stale > 0) {
    badges.push({ label: `${health.stale} hors ligne`, tone: 'danger', icon: 'wifiOff' });
  }
  if (health.outOfSync > 0) {
    badges.push({ label: `${health.outOfSync} à synchroniser`, tone: 'warning', icon: 'clock' });
  }
  if (badges.length === 0) {
    badges.push({ label: 'Synchronisés', tone: 'success', icon: 'checkCircle' });
  }
  return badges;
}

/** Hub column of the console list. */
export function hubContactBadge(health: PharmacySyncHealth | undefined, now: number): SyncBadge {
  if (!health || health.topology !== 'HUB') {
    return { label: 'Cloud direct', tone: 'neutral', icon: 'globe' };
  }
  const label = relativeTime(health.hubLastContactAt, now);
  return health.hubReachable
    ? { label, tone: 'success', icon: 'server' }
    : {
        label: health.hubLastContactAt ? `Injoignable · ${label}` : 'Jamais connecté',
        tone: 'danger',
        icon: 'cloudOff',
      };
}

export function badgeClass(tone: SyncTone): string {
  return tone === 'neutral' ? 'badge' : `badge badge-${tone}`;
}
