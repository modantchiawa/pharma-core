import { describe, expect, it } from 'vitest';
import { columnPath, niceCeiling } from './chart';

describe('niceCeiling', () => {
  it('rounds the peak up to four round gridlines', () => {
    expect(niceCeiling(49_900)).toBe(80_000);
    expect(niceCeiling(40_000)).toBe(40_000);
    expect(niceCeiling(41_000)).toBe(80_000);
    expect(niceCeiling(9_500)).toBe(10_000);
    expect(niceCeiling(1_250)).toBe(2_000);
  });

  it('keeps a positive scale for an empty period', () => {
    expect(niceCeiling(0)).toBe(1);
  });
});

describe('columnPath', () => {
  it('rounds the data-end and keeps the foot on the baseline', () => {
    expect(columnPath(10, 20, 100, 230)).toBe('M10,230V134Q10,130 14,130H26Q30,130 30,134V230Z');
  });

  it('caps the radius on short or thin columns', () => {
    expect(columnPath(0, 4, 1, 10)).toBe('M0,10V10Q0,9 1,9H3Q4,9 4,10V10Z');
  });

  it('draws nothing for a day without sales', () => {
    expect(columnPath(0, 20, 0, 230)).toBe('');
  });
});
