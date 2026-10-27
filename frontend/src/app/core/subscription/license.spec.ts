import { describe, expect, it } from 'vitest';
import { LicenseStateView, LicenseStatus } from '../api/models';
import { CachedLicense, TERMINAL_OFFLINE_WINDOW_MS, daysLeft, effectiveState } from './license';
import {
  ago,
  bannerFor,
  daysLabel,
  frenchDate,
  saleRefusal,
  statusLabel,
  statusTone,
} from './license-text';

const DAY = 86_400_000;
const FETCHED = Date.parse('2026-10-20T12:00:00Z');

function state(overrides: Partial<LicenseStateView> = {}): LicenseStateView {
  return {
    status: 'ACTIVE',
    reason: null,
    deadline: null,
    daysLeft: null,
    blocksAt: null,
    blockedSince: null,
    ...overrides,
  };
}

function cached(view: LicenseStateView, extra: Partial<LicenseStatus> = {}): CachedLicense {
  return {
    fetchedAt: FETCHED,
    status: {
      state: view,
      plan: 'PRO',
      paidUntil: null,
      graceUntil: null,
      license: { serial: 3, issuedAt: null, validUntil: null, verifiedAt: null },
      mode: 'HUB',
      serverTime: new Date(FETCHED).toISOString(),
      ...extra,
    },
  };
}

describe('effectiveState', () => {
  it('knows nothing before the first answer and then never blocks', () => {
    expect(effectiveState(null, FETCHED)).toBeNull();
    expect(saleRefusal(null)).toBeNull();
  });

  it('counts the days down with the terminal clock', () => {
    const deadline = new Date(FETCHED + 6 * DAY).toISOString();
    const view = effectiveState(
      cached(state({ status: 'WARNING', reason: 'PAYMENT_DUE', deadline, daysLeft: 6 })),
      FETCHED + 2 * DAY + 3_600_000,
    );
    expect(view?.status).toBe('WARNING');
    expect(view?.daysLeft).toBe(4);
  });

  it('enters the grace period then blocks on the announced dates, offline', () => {
    const paidUntil = new Date(FETCHED + 2 * DAY).toISOString();
    const graceUntil = new Date(FETCHED + 5 * DAY).toISOString();
    const entry = cached(
      state({
        status: 'WARNING',
        reason: 'PAYMENT_DUE',
        deadline: paidUntil,
        blocksAt: graceUntil,
      }),
      { paidUntil, graceUntil },
    );

    const grace = effectiveState(entry, FETCHED + 3 * DAY);
    expect(grace?.status).toBe('GRACE');
    expect(grace?.reason).toBe('EXPIRED');
    expect(grace?.daysLeft).toBe(2);

    const blocked = effectiveState(entry, FETCHED + 5 * DAY);
    expect(blocked?.status).toBe('BLOCKED');
    expect(blocked?.reason).toBe('EXPIRED');
  });

  it('blocks when the hub license runs out while the terminal is cut off', () => {
    const validUntil = new Date(FETCHED + 3 * DAY).toISOString();
    const entry = cached(state({ blocksAt: validUntil }), {
      license: { serial: 3, issuedAt: null, validUntil, verifiedAt: null },
    });

    expect(effectiveState(entry, FETCHED + 3 * DAY)?.reason).toBe('OFFLINE_TOO_LONG');
  });

  it('stops trusting a state cached more than 15 days ago', () => {
    const entry = cached(state());

    expect(effectiveState(entry, FETCHED + TERMINAL_OFFLINE_WINDOW_MS - 1)?.status).toBe('ACTIVE');
    const late = effectiveState(entry, FETCHED + TERMINAL_OFFLINE_WINDOW_MS);
    expect(late?.status).toBe('BLOCKED');
    expect(late?.reason).toBe('OFFLINE_TOO_LONG');
  });

  it('treats a clock set back as tampering', () => {
    const view = effectiveState(cached(state()), FETCHED - DAY);

    expect(view?.status).toBe('BLOCKED');
    expect(view?.reason).toBe('CLOCK_TAMPERED');
  });

  it('keeps a blocked answer as is', () => {
    const view = state({ status: 'BLOCKED', reason: 'SUSPENDED' });

    expect(effectiveState(cached(view), FETCHED + DAY)).toEqual(view);
  });
});

describe('bannerFor', () => {
  it('shows nothing while the subscription is in order', () => {
    expect(bannerFor(state())).toBeNull();
    expect(bannerFor(null)).toBeNull();
  });

  it('words each reason for the pharmacy', () => {
    const due = bannerFor(
      state({
        status: 'WARNING',
        reason: 'PAYMENT_DUE',
        deadline: '2026-11-03T12:00:00Z',
        daysLeft: 7,
      }),
    );
    expect(due?.tone).toBe('warning');
    expect(due?.title).toBe('Abonnement : plus que 7 jours');
    expect(due?.message).toContain('3 novembre 2026');

    const grace = bannerFor(
      state({ status: 'GRACE', reason: 'EXPIRED', deadline: '2026-11-06T12:00:00Z', daysLeft: 3 }),
    );
    expect(grace?.tone).toBe('danger');
    expect(grace?.title).toBe('Abonnement expiré — régularisez avant le 6 novembre 2026');

    expect(bannerFor(state({ status: 'BLOCKED', reason: 'SUSPENDED' }))?.title).toBe(
      "Abonnement suspendu par l'éditeur",
    );
    expect(bannerFor(state({ status: 'BLOCKED', reason: 'CLOCK_TAMPERED' }))?.title).toBe(
      'Horloge du poste incohérente',
    );
    expect(
      bannerFor(state({ status: 'WARNING', reason: 'OFFLINE_LIMIT', daysLeft: 1 }))?.title,
    ).toBe('Licence à renouveler par Internet sous 1 jour');
    expect(bannerFor(state({ status: 'BLOCKED', reason: 'EXPIRED' }))?.message).toContain(
      "l'ordonnancier",
    );
  });

  it('explains why the counter refuses a sale', () => {
    expect(saleRefusal(state({ status: 'GRACE', reason: 'EXPIRED' }))).toBeNull();
    expect(saleRefusal(state({ status: 'BLOCKED', reason: 'SUSPENDED' }))).toMatch(
      /^Vente impossible — Abonnement suspendu/,
    );
  });
});

describe('statusLabel', () => {
  it('gives the vendor console its badges', () => {
    expect(statusLabel(state())).toBe('Actif');
    expect(statusTone(state())).toBe('success');
    expect(statusLabel(state({ status: 'WARNING', reason: 'PAYMENT_DUE' }))).toBe('À renouveler');
    expect(statusTone(state({ status: 'WARNING', reason: 'PAYMENT_DUE' }))).toBe('warning');
    expect(statusLabel(state({ status: 'GRACE', reason: 'EXPIRED' }))).toBe('En retard · grâce');
    expect(statusLabel(state({ status: 'BLOCKED', reason: 'SUSPENDED' }))).toBe('Suspendu');
    expect(statusTone(state({ status: 'BLOCKED', reason: 'EXPIRED' }))).toBe('danger');
  });
});

describe('formatting', () => {
  it('rounds days up and says how long ago', () => {
    expect(daysLeft(new Date(FETCHED + 2 * DAY + 1).toISOString(), FETCHED)).toBe(3);
    expect(daysLeft(new Date(FETCHED - DAY).toISOString(), FETCHED)).toBe(0);
    expect(frenchDate('2026-11-01T12:00:00Z')).toBe('1er novembre 2026');
    expect(frenchDate('2026-11-21T12:00:00Z')).toBe('21 novembre 2026');
    expect(daysLabel(0)).toBe("moins d'un jour");
    expect(daysLabel(5)).toBe('5 jours');
    expect(ago(new Date(FETCHED - 30_000).toISOString(), FETCHED)).toBe("à l'instant");
    expect(ago(new Date(FETCHED - 12 * 60_000).toISOString(), FETCHED)).toBe('il y a 12 min');
    expect(ago(new Date(FETCHED - 3 * DAY).toISOString(), FETCHED)).toBe('il y a 3 jours');
    expect(ago(null, FETCHED)).toBe('jamais');
  });
});
