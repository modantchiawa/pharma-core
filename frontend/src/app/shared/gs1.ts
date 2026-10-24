/**
 * GS1 element string parser for the 2D codes printed on medicine boxes (DataMatrix GS1,
 * GS1-128). A scanner in keyboard mode types the application identifiers one after the other;
 * variable-length fields end with the FNC1 group separator (ASCII 29) or at the end of the code.
 *
 * Supported identifiers: 01 GTIN-14 (fixed 14), 11 production date, 15 best-before, 17 expiry
 * (fixed YYMMDD), 10 lot (variable, 20 max), 21 serial (variable, 20 max), 30 count (variable, 8 max).
 */
export interface Gs1Code {
  /** GTIN-14 as printed. */
  gtin: string | null;
  /** EAN-13 derived from the GTIN (leading indicator digit dropped). */
  ean13: string | null;
  lot: string | null;
  serial: string | null;
  /** ISO date (YYYY-MM-DD); a day of "00" means the last day of the month. */
  expiryDate: string | null;
  productionDate: string | null;
  count: number | null;
}

const GS = '\u001d';
const FIXED_LENGTH: Record<string, number> = { '01': 14, '11': 6, '15': 6, '17': 6 };
const VARIABLE_MAX: Record<string, number> = { '10': 20, '21': 20, '30': 8 };

/** True when the scanned text looks like a GS1 element string rather than a plain EAN/CIP. */
export function looksLikeGs1(raw: string): boolean {
  const text = stripSymbology(raw);
  return text.includes(GS) || /^01\d{14}/.test(text);
}

/** Parses a scanned code; returns null when it is not a GS1 element string. */
export function parseGs1(raw: string): Gs1Code | null {
  const text = stripSymbology(raw);
  if (!looksLikeGs1(raw)) {
    return null;
  }
  const code: Gs1Code = {
    gtin: null,
    ean13: null,
    lot: null,
    serial: null,
    expiryDate: null,
    productionDate: null,
    count: null,
  };
  let position = 0;
  while (position < text.length) {
    if (text[position] === GS) {
      position += 1;
      continue;
    }
    const ai = text.slice(position, position + 2);
    position += 2;
    const fixed = FIXED_LENGTH[ai];
    const max = VARIABLE_MAX[ai];
    let value: string;
    if (fixed !== undefined) {
      value = text.slice(position, position + fixed);
      if (value.length < fixed) {
        return null;
      }
      position += fixed;
    } else if (max !== undefined) {
      const separator = text.indexOf(GS, position);
      const end = separator === -1 ? text.length : separator;
      value = text.slice(position, Math.min(end, position + max));
      position = separator === -1 ? text.length : separator + 1;
    } else {
      // Unknown identifier: the rest cannot be delimited safely
      return code.gtin || code.lot || code.expiryDate ? code : null;
    }
    switch (ai) {
      case '01':
        if (!/^\d{14}$/.test(value)) {
          return null;
        }
        code.gtin = value;
        code.ean13 = gtinToEan13(value);
        break;
      case '17':
        code.expiryDate = gs1Date(value);
        break;
      case '11':
        code.productionDate = gs1Date(value);
        break;
      case '15':
        code.expiryDate = code.expiryDate ?? gs1Date(value);
        break;
      case '10':
        code.lot = value || null;
        break;
      case '21':
        code.serial = value || null;
        break;
      case '30':
        code.count = /^\d+$/.test(value) ? Number(value) : null;
        break;
    }
  }
  return code;
}

/** GTIN-14 → EAN-13: a leading indicator digit of 0 is dropped; other indicators keep the 14 digits. */
export function gtinToEan13(gtin: string): string | null {
  if (!/^\d{14}$/.test(gtin)) {
    return null;
  }
  return gtin.startsWith('0') ? gtin.slice(1) : gtin;
}

/** YYMMDD → YYYY-MM-DD; "DD = 00" means the last day of the month (GS1 general specifications). */
export function gs1Date(yymmdd: string): string | null {
  if (!/^\d{6}$/.test(yymmdd)) {
    return null;
  }
  const year = 2000 + Number(yymmdd.slice(0, 2));
  const month = Number(yymmdd.slice(2, 4));
  let day = Number(yymmdd.slice(4, 6));
  if (month < 1 || month > 12) {
    return null;
  }
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  if (day === 0) {
    day = lastDay;
  }
  if (day > lastDay) {
    return null;
  }
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function stripSymbology(raw: string): string {
  // Some scanners prefix the symbology identifier (]d2 DataMatrix, ]C1 GS1-128, ]Q3 QR)
  return raw.replace(/^\](d2|C1|Q3|e0)/, '').trim();
}
