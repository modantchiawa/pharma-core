import { Pipe, PipeTransform, inject } from '@angular/core';
import { CurrencyService } from '../core/ui/currency.service';

const formatters = new Map<string, Intl.NumberFormat>();

/** Formats an amount in minor units: 1250 → "1 250 F CFA" in XOF, 1250 → "12,50 €" in EUR. */
@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly currency = inject(CurrencyService);

  transform(minor: number | null | undefined, currency?: string, locale = 'fr-FR'): string {
    if (minor === null || minor === undefined) {
      return '—';
    }
    const code = currency ?? this.currency.current();
    const key = `${locale}|${code}`;
    let formatter = formatters.get(key);
    if (!formatter) {
      formatter = new Intl.NumberFormat(locale, { style: 'currency', currency: code });
      formatters.set(key, formatter);
    }
    const exponent = currency
      ? (formatter.resolvedOptions().maximumFractionDigits ?? 2)
      : this.currency.exponent();
    return formatter.format(minor / 10 ** exponent);
  }
}
