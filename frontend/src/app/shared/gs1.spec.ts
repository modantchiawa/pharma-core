import { describe, expect, it } from 'vitest';
import { gs1Date, gtinToEan13, looksLikeGs1, parseGs1 } from './gs1';

const GS = '\u001d';

describe('GS1 DataMatrix parsing', () => {
  it('reads GTIN, expiry and lot in the usual order', () => {
    const code = parseGs1(`010340093000001517280331${GS}10ABC123`);
    expect(code).toEqual({
      gtin: '03400930000015',
      ean13: '3400930000015',
      lot: 'ABC123',
      serial: null,
      expiryDate: '2028-03-31',
      productionDate: null,
      count: null,
    });
  });

  it('accepts a variable field in the middle and a symbology prefix', () => {
    const code = parseGs1(`]d20103400930000015` + `10L-7${GS}` + `172711002112345`);
    expect(code?.lot).toBe('L-7');
    expect(code?.expiryDate).toBe('2027-11-30');
    expect(code?.serial).toBe('12345');
  });

  it('turns day 00 into the last day of the month, leap years included', () => {
    expect(gs1Date('280200')).toBe('2028-02-29');
    expect(gs1Date('270200')).toBe('2027-02-28');
    expect(gs1Date('261305')).toBeNull();
    expect(gs1Date('260431')).toBeNull();
  });

  it('derives the EAN-13 from a GTIN-14 with indicator 0 only', () => {
    expect(gtinToEan13('03400930000015')).toBe('3400930000015');
    expect(gtinToEan13('13400930000012')).toBe('13400930000012');
    expect(gtinToEan13('3400930000015')).toBeNull();
  });

  it('leaves plain EAN or CIP scans alone', () => {
    expect(looksLikeGs1('3400930000015')).toBe(false);
    expect(parseGs1('3400930000015')).toBeNull();
    expect(parseGs1('01ABC')).toBeNull();
  });
});
