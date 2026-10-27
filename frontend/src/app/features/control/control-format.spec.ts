import { EmployeeControl } from '../../core/api/models';
import {
  METRICS,
  activityDays,
  flaggedCount,
  hoursRows,
  isoDaysAgo,
  metricFoot,
  returnRefund,
  severityBadge,
} from './control-format';

function employee(overrides: Partial<EmployeeControl> = {}): EmployeeControl {
  return {
    userId: 'u1',
    displayName: 'Dr Ibrahim Souley',
    active: true,
    sales: 26,
    salesNetCents: 52_000,
    cancellations: 6,
    cancellationsCents: 12_000,
    cancellationsAfterPayment: 6,
    cancellationRate: 23.1,
    discountsCents: 0,
    supervisorOverrides: 0,
    adjustments: 1,
    adjustmentsValueCents: -2_000,
    writeOffs: 3,
    writeOffsValueCents: 18_000,
    returns: 0,
    returnsCents: 0,
    cashSessions: 2,
    cashVarianceCents: 2_500,
    worstCashVarianceCents: -2_500,
    countDiscrepancies: 1,
    outsideHours: 1,
    shortfallSales: 0,
    flags: [
      {
        metric: 'CANCELLATIONS_AFTER_PAYMENT',
        flagged: true,
        value: 6,
        teamMedian: 0,
        explanation: '',
      },
      { metric: 'OUTSIDE_HOURS', flagged: true, value: 1, teamMedian: 0, explanation: '' },
      { metric: 'RETURNS', flagged: false, value: 0, teamMedian: 0, explanation: '' },
    ],
    ...overrides,
  };
}

describe('control format', () => {
  it('lists every metric once', () => {
    expect(new Set(METRICS.map((m) => m.metric)).size).toBe(10);
  });

  it('counts the flags and shows the figure behind a metric', () => {
    const e = employee();
    expect(flaggedCount(e)).toBe(2);
    expect(metricFoot(e, 'CANCELLATIONS_AFTER_PAYMENT')).toBe('6 annulation(s) au total');
    expect(metricFoot(e, 'STOCK_LOSSES')).toBe('1 ajustement(s) · 3 sortie(s)');
    expect(metricFoot(e, 'RETURNS')).toBeNull();
  });

  it('maps severities to badges', () => {
    expect(severityBadge({ severity: 'CRITICAL' })).toBe('badge-danger');
    expect(severityBadge({ severity: 'WARNING' })).toBe('badge-warning');
    expect(severityBadge({ severity: 'INFO' })).toBe('badge-info');
  });

  it('refunds a partial return pro rata, rounded like the server', () => {
    expect(returnRefund(8_000, 4, 3)).toBe(6_000);
    expect(returnRefund(1_000, 3, 1)).toBe(333);
    expect(returnRefund(1_000, 3, 2)).toBe(667);
    expect(returnRefund(1_000, 3, 0)).toBe(0);
  });

  it('edits the activity hours as seven rows and refuses inverted slots', () => {
    const rows = hoursRows([
      { day: 'MONDAY', open: '07:00', close: '22:00' },
      { day: 'SUNDAY', open: '09:00:00', close: '13:00:00' },
    ]);
    expect(rows).toHaveLength(7);
    expect(rows[0]).toEqual({ day: 'MONDAY', open: true, from: '07:00', to: '22:00' });
    expect(rows[1].open).toBe(false);
    expect(rows[6]).toEqual({ day: 'SUNDAY', open: true, from: '09:00', to: '13:00' });

    expect(activityDays(rows)).toEqual({
      days: [
        { day: 'MONDAY', open: '07:00', close: '22:00' },
        { day: 'SUNDAY', open: '09:00', close: '13:00' },
      ],
      problem: null,
    });
    rows[0].to = '06:00';
    expect(activityDays(rows).problem).toBe('Lundi : la fermeture doit suivre l’ouverture');
    expect(activityDays(rows.map((r) => ({ ...r, open: false }))).problem).toBe(
      'Indiquez au moins un jour d’ouverture',
    );
  });

  it('computes period bounds from today', () => {
    const now = new Date(2026, 9, 27, 15, 0);
    expect(isoDaysAgo(0, now)).toBe('2026-10-27');
    expect(isoDaysAgo(29, now)).toBe('2026-09-28');
  });
});
