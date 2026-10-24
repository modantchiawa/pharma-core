import { Injectable, computed, signal } from '@angular/core';

const EXPONENTS: Record<string, number> = {
  XOF: 0,
  XAF: 0,
  GNF: 0,
  CDF: 2,
  EUR: 2,
  USD: 2,
  MAD: 2,
  NGN: 2,
  GHS: 2,
};

/** Currency of the pharmacy, driving how minor units are displayed (XOF has no decimals). */
@Injectable({ providedIn: 'root' })
export class CurrencyService {
  private readonly code = signal<string>(localStorage.getItem('pharmacore.currency') ?? 'XOF');

  readonly current = this.code.asReadonly();
  readonly exponent = computed(() => EXPONENTS[this.code()] ?? 2);
  /** 1 for XOF (amounts are whole francs), 100 for EUR. */
  readonly minorUnitsPerUnit = computed(() => 10 ** this.exponent());

  set(code: string): void {
    if (code && code !== this.code()) {
      this.code.set(code);
      localStorage.setItem('pharmacore.currency', code);
    }
  }

  /** User-typed amount (e.g. "1250" or "12,50") to minor units. */
  parse(input: string | number | null | undefined): number {
    if (input === null || input === undefined || input === '') {
      return 0;
    }
    const value =
      typeof input === 'number'
        ? input
        : Number(String(input).replace(/\s/g, '').replace(',', '.'));
    return Number.isFinite(value) ? Math.round(value * this.minorUnitsPerUnit()) : 0;
  }

  toUnits(minor: number): number {
    return minor / this.minorUnitsPerUnit();
  }
}
