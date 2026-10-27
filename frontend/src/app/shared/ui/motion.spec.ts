import { describe, expect, it } from 'vitest';
import {
  countDuration,
  cubicBezier,
  easeDecelerate,
  interpolate,
  sparklinePath,
  staggerDelay,
} from './motion';

describe('cubicBezier', () => {
  it('starts at 0, ends at 1 and stays monotonic for the decelerate curve', () => {
    expect(easeDecelerate(0)).toBe(0);
    expect(easeDecelerate(1)).toBe(1);
    let previous = 0;
    for (let i = 1; i <= 20; i++) {
      const value = easeDecelerate(i / 20);
      expect(value).toBeGreaterThanOrEqual(previous);
      previous = value;
    }
  });

  it('is the identity for a linear curve', () => {
    const linear = cubicBezier(0, 0, 1, 1);
    expect(linear(0.25)).toBeCloseTo(0.25, 4);
    expect(linear(0.7)).toBeCloseTo(0.7, 4);
  });

  it('matches the CSS "ease" keyword at known points', () => {
    const ease = cubicBezier(0.25, 0.1, 0.25, 1);
    expect(ease(0.5)).toBeCloseTo(0.8024, 3);
  });

  it('decelerates: most of the way is covered in the first half', () => {
    expect(easeDecelerate(0.5)).toBeGreaterThan(0.85);
  });
});

describe('interpolate', () => {
  it('lands exactly on the bounds', () => {
    expect(interpolate(0, 125_000, 0)).toBe(0);
    expect(interpolate(0, 125_000, 1)).toBe(125_000);
    expect(interpolate(0, 125_000, 1.4)).toBe(125_000);
  });

  it('only shows whole minor units', () => {
    for (let i = 1; i < 10; i++) {
      expect(Number.isInteger(interpolate(0, 3333, i / 10))).toBe(true);
    }
  });

  it('counts down as well as up', () => {
    const halfway = interpolate(5000, 1000, 0.5, (t) => t);
    expect(halfway).toBe(3000);
  });
});

describe('staggerDelay', () => {
  it('steps each item and caps the cascade', () => {
    expect(staggerDelay(0)).toBe(0);
    expect(staggerDelay(1)).toBe(45);
    expect(staggerDelay(3, 30)).toBe(90);
    expect(staggerDelay(7)).toBe(staggerDelay(25));
    expect(staggerDelay(-2)).toBe(0);
  });
});

describe('countDuration', () => {
  it('is zero without change and grows with the size of the change', () => {
    expect(countDuration(10, 10, 200, 800)).toBe(0);
    const small = countDuration(0, 50, 200, 800);
    const large = countDuration(0, 5_000_000, 200, 800);
    expect(small).toBeGreaterThanOrEqual(200);
    expect(large).toBeGreaterThan(small);
    expect(countDuration(0, 1e12, 200, 800)).toBe(800);
  });
});

describe('sparklinePath', () => {
  it('maps the lowest value to the bottom and the highest to the top', () => {
    const { line } = sparklinePath([0, 10, 5], 100, 20, 2);
    expect(line).toBe('M0,18L50,2L100,10');
  });

  it('closes the area along the baseline', () => {
    const { area } = sparklinePath([1, 2], 40, 10, 0);
    expect(area).toBe('M0,10L40,0L40,10L0,10Z');
  });

  it('draws a flat series and a single point at mid-height', () => {
    expect(sparklinePath([3, 3, 3], 60, 20).line).toBe('M0,10L30,10L60,10');
    expect(sparklinePath([7], 60, 20).line).toBe('M0,10L60,10');
    expect(sparklinePath([], 60, 20)).toEqual({ line: '', area: '' });
  });
});
