import { Pipe, PipeTransform } from '@angular/core';

const formatters = new Map<string, Intl.NumberFormat>();

/** Formats an amount in minor units (cents): {{ 1234 | money }} → 12,34 €. */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(cents: number | null | undefined, currency = 'EUR', locale = 'fr-FR'): string {
    if (cents === null || cents === undefined) {
      return '—';
    }
    const key = `${locale}|${currency}`;
    let formatter = formatters.get(key);
    if (!formatter) {
      formatter = new Intl.NumberFormat(locale, { style: 'currency', currency });
      formatters.set(key, formatter);
    }
    return formatter.format(cents / 100);
  }
}
