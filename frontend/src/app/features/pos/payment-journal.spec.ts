import { describe, expect, it } from 'vitest';
import { PaymentJournalEntry } from '../../core/api/models';
import {
  categoryKey,
  categoryLabel,
  entriesTotal,
  inCategory,
  matchesEntry,
  parseCategoryKey,
  providerLabel,
  shiftDate,
} from './payment-journal';

function entry(
  ticketNumber: string,
  method: string,
  provider: string | null,
  amountCents: number,
  confirmationStatus: PaymentJournalEntry['confirmationStatus'] = 'CONFIRMED',
): PaymentJournalEntry {
  return {
    paymentId: ticketNumber,
    saleId: ticketNumber,
    ticketNumber,
    occurredAt: '2026-10-30T09:15:00Z',
    terminalId: 'CAISSE-1',
    cashierName: 'Aïcha Moussa',
    method,
    provider,
    reference: provider ? `REF-${ticketNumber}` : null,
    amountCents,
    confirmationStatus,
  };
}

describe('payment journal', () => {
  it('names a category after its operator, or its method without one', () => {
    expect(categoryLabel({ method: 'MOBILE_MONEY', provider: 'MYNITA' })).toBe('MyNITA');
    expect(categoryLabel({ method: 'MOBILE_MONEY', provider: 'ZAMANI_CASH' })).toBe('Zamani Cash');
    expect(categoryLabel({ method: 'CASH', provider: null })).toBe('Espèces');
    expect(categoryLabel({ method: 'CARD', provider: 'VISA' })).toBe('Carte · Visa');
    expect(providerLabel('AMANA_TRANSFERT')).toBe('Amana Transfert');
  });

  it('round-trips the category through the address', () => {
    const nita = { method: 'MOBILE_MONEY', provider: 'MYNITA' };
    expect(parseCategoryKey(categoryKey(nita))).toEqual(nita);
    expect(parseCategoryKey(categoryKey({ method: 'CASH', provider: null }))).toEqual({
      method: 'CASH',
      provider: null,
    });
    expect(parseCategoryKey('')).toBeNull();
  });

  it('keeps the entries of one category and totals them without the rejected ones', () => {
    const entries = [
      entry('T-1', 'MOBILE_MONEY', 'MYNITA', 5_000),
      entry('T-2', 'MOBILE_MONEY', 'MYNITA', 2_500, 'PENDING'),
      entry('T-3', 'MOBILE_MONEY', 'MYNITA', 9_000, 'REJECTED'),
      entry('T-4', 'MOBILE_MONEY', 'AIRTEL_MONEY', 1_000),
      entry('T-5', 'CASH', null, 700, 'NOT_REQUIRED'),
    ];
    const nita = entries.filter((e) =>
      inCategory(e, { method: 'MOBILE_MONEY', provider: 'MYNITA' }),
    );
    expect(nita.map((e) => e.ticketNumber)).toEqual(['T-1', 'T-2', 'T-3']);
    expect(entriesTotal(nita)).toEqual({ count: 2, totalCents: 7_500 });
    expect(entries.filter((e) => inCategory(e, null))).toHaveLength(5);
  });

  it('finds an entry by ticket, reference or cashier', () => {
    const nita = entry('T-42', 'MOBILE_MONEY', 'MYNITA', 5_000);
    expect(matchesEntry(nita, '')).toBe(true);
    expect(matchesEntry(nita, 't-42')).toBe(true);
    expect(matchesEntry(nita, 'ref-t-42')).toBe(true);
    expect(matchesEntry(nita, 'aïcha')).toBe(true);
    expect(matchesEntry(nita, 'T-43')).toBe(false);
  });

  it('moves from day to day across months and years', () => {
    expect(shiftDate('2026-10-31', 1)).toBe('2026-11-01');
    expect(shiftDate('2026-01-01', -1)).toBe('2025-12-31');
  });
});
