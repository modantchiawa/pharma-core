import { PaymentCategoryTotal, PaymentJournalEntry } from '../../core/api/models';
import { IconName } from '../../shared/ui/icons';

/** Pure helpers of the receipts-by-category screen ("encaissements par caisse"). */

export const METHOD_LABELS: Record<string, string> = {
  CASH: 'Espèces',
  CARD: 'Carte',
  CHECK: 'Chèque',
  THIRD_PARTY_PAYER: 'Tiers payant',
  VOUCHER: 'Avoir',
  MOBILE_MONEY: 'Mobile money',
  CREDIT: 'Crédit client',
};

const METHOD_ICONS: Record<string, IconName> = {
  CASH: 'cash',
  CARD: 'card',
  CHECK: 'receipt',
  THIRD_PARTY_PAYER: 'insurance',
  VOUCHER: 'receipt',
  MOBILE_MONEY: 'mobileMoney',
  CREDIT: 'credit',
};

const PROVIDER_LABELS: Record<string, string> = {
  MYNITA: 'MyNITA',
  AIRTEL_MONEY: 'Airtel Money',
  MOOV_MONEY: 'Moov Money',
  ORANGE_MONEY: 'Orange Money',
  ZAMANI_CASH: 'Zamani Cash',
  WAVE: 'Wave',
};

/** "ZAMANI_CASH" → "Zamani Cash"; known operators keep their own spelling (MyNITA). */
export function providerLabel(provider: string): string {
  return (
    PROVIDER_LABELS[provider] ??
    provider
      .toLowerCase()
      .split('_')
      .filter(Boolean)
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(' ')
  );
}

export type CategoryRef = Pick<PaymentCategoryTotal, 'method' | 'provider'>;

/** Stable key of a category, also used in the address ({@code ?category=MOBILE_MONEY:MYNITA}). */
export function categoryKey(category: CategoryRef): string {
  return `${category.method}:${category.provider ?? ''}`;
}

export function parseCategoryKey(key: string | null | undefined): CategoryRef | null {
  if (!key) {
    return null;
  }
  const [method, provider] = key.split(':');
  return method ? { method, provider: provider || null } : null;
}

/** "MyNITA", "Espèces", "Carte · Visa": the operator when there is one, the method otherwise. */
export function categoryLabel(category: CategoryRef): string {
  const method = METHOD_LABELS[category.method] ?? category.method;
  if (!category.provider) {
    return method;
  }
  const provider = providerLabel(category.provider);
  return category.method === 'MOBILE_MONEY' ? provider : `${method} · ${provider}`;
}

export function categoryIcon(category: CategoryRef): IconName {
  return METHOD_ICONS[category.method] ?? 'banknote';
}

/** Does an entry belong to the category (a method without operator matches only entries without one)? */
export function inCategory(entry: PaymentJournalEntry, category: CategoryRef | null): boolean {
  return !category || (entry.method === category.method && entry.provider === category.provider);
}

/** Entries matching the typed text: ticket, operator reference, cashier or station. */
export function matchesEntry(entry: PaymentJournalEntry, term: string): boolean {
  const text = term.trim().toLowerCase();
  if (!text) {
    return true;
  }
  return [entry.ticketNumber, entry.reference, entry.cashierName, entry.terminalId].some((v) =>
    (v ?? '').toLowerCase().includes(text),
  );
}

/** Count and amount of the entries, receipts rejected on the operator's statement left out. */
export function entriesTotal(entries: PaymentJournalEntry[]): {
  count: number;
  totalCents: number;
} {
  const kept = entries.filter((e) => e.confirmationStatus !== 'REJECTED');
  return { count: kept.length, totalCents: kept.reduce((sum, e) => sum + e.amountCents, 0) };
}

/** ISO date {@code days} after (or before) {@code iso}, calendar arithmetic only. */
export function shiftDate(iso: string, days: number): string {
  const date = new Date(Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10)));
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}
