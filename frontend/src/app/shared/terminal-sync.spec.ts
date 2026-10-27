import { describe, expect, it } from 'vitest';
import { HubView, PharmacySyncHealth, TerminalBoard, TerminalView } from '../core/api/models';
import {
  badgeClass,
  compactDuration,
  forgettable,
  hubBadge,
  hubContactBadge,
  observedLabel,
  pharmacyBadges,
  plural,
  relativeTime,
  terminalBadge,
} from './terminal-sync';

const NOW = Date.parse('2026-10-27T10:00:00Z');
const MIN = 60_000;

function ago(ms: number): string {
  return new Date(NOW - ms).toISOString();
}

function terminal(overrides: Partial<TerminalView>): TerminalView {
  return {
    terminalId: 'DEMO-1',
    appVersion: 'demo',
    lastSeenAt: ago(30_000),
    lastSyncAt: ago(2 * MIN),
    pendingSales: 0,
    rejectedSales: 0,
    state: 'SYNCED',
    ...overrides,
  };
}

function board(overrides: Partial<TerminalBoard>): TerminalBoard {
  return {
    observedAt: ago(0),
    relayedByHub: false,
    staleAfterSeconds: 300,
    counts: { total: 0, synced: 0, pending: 0, stale: 0, never: 0, pendingSales: 0 },
    terminals: [],
    ...overrides,
  };
}

function health(overrides: Partial<PharmacySyncHealth>): PharmacySyncHealth {
  return {
    tenantId: 't-1',
    topology: 'CLOUD',
    terminals: 3,
    outOfSync: 0,
    stale: 0,
    pendingSales: 0,
    hubLastContactAt: null,
    hubReachable: false,
    observedAt: ago(0),
    ...overrides,
  };
}

describe('relative time', () => {
  it('reads like a person would say it', () => {
    expect(relativeTime(ago(10_000), NOW)).toBe("à l'instant");
    expect(relativeTime(ago(44_999), NOW)).toBe("à l'instant");
    expect(relativeTime(ago(45_000), NOW)).toBe('il y a 1 min');
    expect(relativeTime(ago(5 * MIN + 59_000), NOW)).toBe('il y a 5 min');
    expect(relativeTime(ago(59 * MIN), NOW)).toBe('il y a 59 min');
    expect(relativeTime(ago(60 * MIN), NOW)).toBe('il y a 1 h');
    expect(relativeTime(ago(23 * 60 * MIN), NOW)).toBe('il y a 23 h');
    expect(relativeTime(ago(2 * 24 * 60 * MIN + 5 * MIN), NOW)).toBe('il y a 2 j');
  });

  it('handles missing, invalid and future dates', () => {
    expect(relativeTime(null, NOW)).toBe('jamais');
    expect(relativeTime(undefined, NOW)).toBe('jamais');
    expect(relativeTime('pas une date', NOW)).toBe('jamais');
    expect(relativeTime(new Date(NOW + 5 * MIN), NOW)).toBe("à l'instant");
    expect(relativeTime(new Date(NOW - 3 * MIN), NOW)).toBe('il y a 3 min');
  });

  it('formats compact durations, never below one minute', () => {
    expect(compactDuration(0)).toBe('1 min');
    expect(compactDuration(-5)).toBe('1 min');
    expect(compactDuration(6 * MIN)).toBe('6 min');
    expect(compactDuration(3 * 60 * MIN)).toBe('3 h');
    expect(compactDuration(49 * 60 * MIN)).toBe('2 j');
    expect(plural(1, 'vente', 'ventes')).toBe('1 vente');
    expect(plural(0, 'vente', 'ventes')).toBe('0 vente');
    expect(plural(3, 'vente', 'ventes')).toBe('3 ventes');
  });
});

describe('register badges', () => {
  it('labels each synchronization state', () => {
    const observed = ago(0);
    expect(terminalBadge(terminal({}), observed)).toEqual({
      label: 'Synchronisé',
      tone: 'success',
      icon: 'checkCircle',
    });
    expect(terminalBadge(terminal({ state: 'PENDING', pendingSales: 3 }), observed).label).toBe(
      '3 ventes en attente',
    );
    expect(terminalBadge(terminal({ state: 'PENDING', pendingSales: 1 }), observed).label).toBe(
      '1 vente en attente',
    );
    expect(terminalBadge(terminal({ state: 'NEVER', lastSyncAt: null }), observed)).toMatchObject({
      label: 'Jamais synchronisé',
      tone: 'info',
    });
  });

  it('measures the silence of an off-line register at the observation instant', () => {
    const offline = terminal({ state: 'STALE', lastSeenAt: ago(2 * 24 * 60 * MIN) });
    expect(terminalBadge(offline, ago(0))).toEqual({
      label: 'Hors ligne depuis 2 j',
      tone: 'danger',
      icon: 'wifiOff',
    });
    // relayed by a hub last heard of 47 hours ago: the register went quiet one hour before that
    expect(terminalBadge(offline, ago(47 * 60 * MIN)).label).toBe('Hors ligne depuis 1 h');
  });

  it('maps tones to badge classes', () => {
    expect(badgeClass('success')).toBe('badge badge-success');
    expect(badgeClass('danger')).toBe('badge badge-danger');
    expect(badgeClass('neutral')).toBe('badge');
  });

  it('offers to forget only off-line registers holding no sale', () => {
    const list = board({
      terminals: [
        terminal({ terminalId: 'A' }),
        terminal({ terminalId: 'B', state: 'STALE' }),
        terminal({ terminalId: 'C', state: 'STALE', pendingSales: 2 }),
        terminal({ terminalId: 'D', state: 'NEVER' }),
      ],
    });
    expect(forgettable(list).map((t) => t.terminalId)).toEqual(['B']);
  });

  it('says how fresh the picture is', () => {
    expect(observedLabel(board({}), NOW)).toBe("Actualisé à l'instant");
    expect(
      observedLabel(board({ relayedByHub: true, observedAt: ago(2 * 24 * 60 * MIN) }), NOW),
    ).toBe('Vu par le cloud il y a 2 j');
  });
});

describe('hub and pharmacy badges', () => {
  const hub: HubView = {
    nodeId: 'hub-1',
    appVersion: '1.4.0',
    pairedAt: ago(30 * 24 * 60 * MIN),
    lastContactAt: ago(20_000),
    lastPushAt: ago(20_000),
    lastPullAt: ago(20_000),
    reachable: true,
  };

  it('describes the hub link', () => {
    expect(hubBadge(null, NOW).label).toBe('Pas de serveur local');
    expect(hubBadge(hub, NOW)).toMatchObject({
      label: 'En liaison avec le cloud',
      tone: 'success',
    });
    expect(
      hubBadge({ ...hub, reachable: false, lastContactAt: ago(3 * 60 * MIN) }, NOW),
    ).toMatchObject({ label: 'Injoignable depuis 3 h', tone: 'danger' });
    expect(hubBadge({ ...hub, reachable: false, lastContactAt: null }, NOW).label).toBe(
      'Jamais connecté au cloud',
    );
  });

  it('summarises a pharmacy in the console list', () => {
    expect(pharmacyBadges(undefined)).toEqual([
      { label: 'Aucun poste', tone: 'neutral', icon: 'monitor' },
    ]);
    expect(pharmacyBadges(health({})).map((b) => b.label)).toEqual(['Synchronisés']);
    expect(pharmacyBadges(health({ stale: 1, outOfSync: 2 })).map((b) => b.label)).toEqual([
      '1 hors ligne',
      '2 à synchroniser',
    ]);
  });

  it('shows the hub last contact only for pharmacies in hub mode', () => {
    expect(hubContactBadge(health({}), NOW).label).toBe('Cloud direct');
    expect(
      hubContactBadge(
        health({ topology: 'HUB', hubReachable: true, hubLastContactAt: ago(20_000) }),
        NOW,
      ),
    ).toMatchObject({ label: "à l'instant", tone: 'success' });
    expect(
      hubContactBadge(health({ topology: 'HUB', hubLastContactAt: ago(2 * 24 * 60 * MIN) }), NOW),
    ).toMatchObject({ label: 'Injoignable · il y a 2 j', tone: 'danger' });
    expect(hubContactBadge(health({ topology: 'HUB' }), NOW).label).toBe('Jamais connecté');
  });
});
