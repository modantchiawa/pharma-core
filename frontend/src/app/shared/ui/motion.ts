/**
 * Motion system shared by the components that animate from code (Web Animations API, counters).
 * The same durations and curves exist as CSS tokens in styles.css (--motion-*, --ease-*).
 * `reveal` is kept for the rare full-screen moments (theme switch, sale recorded).
 */
export const DURATION = { short: 120, medium: 200, long: 320, reveal: 480 } as const;

/** CSS easing strings matching the --ease-* tokens, for element.animate(). */
export const EASING = {
  standard: 'cubic-bezier(0.2, 0, 0, 1)',
  emphasized: 'cubic-bezier(0.3, 0, 0, 1)',
  decelerate: 'cubic-bezier(0.05, 0.7, 0.1, 1)',
  accelerate: 'cubic-bezier(0.3, 0, 0.8, 0.15)',
} as const;

export type Easing = (t: number) => number;

/**
 * The cubic Bézier timing function of CSS (P0 = 0,0 and P3 = 1,1), solved for x by Newton's
 * method with a bisection fallback, so a counter follows the same curve as the CSS around it.
 */
export function cubicBezier(x1: number, y1: number, x2: number, y2: number): Easing {
  const cx = 3 * x1;
  const bx = 3 * (x2 - x1) - cx;
  const ax = 1 - cx - bx;
  const cy = 3 * y1;
  const by = 3 * (y2 - y1) - cy;
  const ay = 1 - cy - by;
  const sampleX = (t: number) => ((ax * t + bx) * t + cx) * t;
  const sampleY = (t: number) => ((ay * t + by) * t + cy) * t;
  const slopeX = (t: number) => (3 * ax * t + 2 * bx) * t + cx;
  const solve = (x: number) => {
    let t = x;
    for (let i = 0; i < 8; i++) {
      const error = sampleX(t) - x;
      if (Math.abs(error) < 1e-6) {
        return t;
      }
      const slope = slopeX(t);
      if (Math.abs(slope) < 1e-6) {
        break;
      }
      t -= error / slope;
    }
    let low = 0;
    let high = 1;
    t = x;
    while (high - low > 1e-6) {
      if (sampleX(t) < x) {
        low = t;
      } else {
        high = t;
      }
      t = (low + high) / 2;
    }
    return t;
  };
  return (progress: number) => {
    if (progress <= 0) {
      return 0;
    }
    if (progress >= 1) {
      return 1;
    }
    return sampleY(solve(progress));
  };
}

/** Fast start, long gentle landing: counters and drawn charts. */
export const easeDecelerate = /* @__PURE__ */ cubicBezier(0.05, 0.7, 0.1, 1);

/**
 * Value of a counter at a point of its run, eased and rounded to whole minor units: the figure
 * on screen is always an amount that could exist, and lands exactly on the target.
 */
export function interpolate(
  from: number,
  to: number,
  progress: number,
  easing: Easing = easeDecelerate,
): number {
  if (progress >= 1) {
    return to;
  }
  if (progress <= 0) {
    return from;
  }
  return Math.round(from + (to - from) * easing(progress));
}

/**
 * Delay of the n-th item of a cascade: a constant step, capped so that a long list does not
 * keep its last cards waiting (the eye reads the first few, the rest arrive together).
 */
export function staggerDelay(index: number, step = 45, maxItems = 8): number {
  return Math.max(0, Math.min(index, maxItems - 1)) * step;
}

/**
 * Length of a counter run: a small change ticks quickly, a large one rolls longer, within bounds
 * so that a figure never lags behind what the user just did.
 */
export function countDuration(from: number, to: number, min: number, max: number): number {
  const delta = Math.abs(to - from);
  if (delta === 0) {
    return 0;
  }
  const scaled = min + (Math.log10(delta + 1) / 7) * (max - min);
  return Math.round(Math.min(max, Math.max(min, scaled)));
}

export function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Runs a value from `from` to `to` frame by frame and returns a function that stops it.
 * With reduced motion (or nothing to animate) the final value is applied at once.
 */
export function tween(
  from: number,
  to: number,
  duration: number,
  onFrame: (value: number) => void,
  easing: Easing = easeDecelerate,
): () => void {
  if (duration <= 0 || from === to || prefersReducedMotion()) {
    onFrame(to);
    return () => undefined;
  }
  let frame = 0;
  let start: number | null = null;
  const step = (now: number) => {
    start ??= now;
    const progress = Math.min(1, (now - start) / duration);
    onFrame(interpolate(from, to, progress, easing));
    if (progress < 1) {
      frame = requestAnimationFrame(step);
    }
  };
  frame = requestAnimationFrame(step);
  return () => cancelAnimationFrame(frame);
}

/**
 * Line and area of a sparkline through the values, in a width × height box with an inner
 * vertical padding so that the stroke is never clipped. A flat or single-point series is drawn
 * as a horizontal line at mid-height.
 */
export function sparklinePath(
  values: readonly number[],
  width: number,
  height: number,
  padding = 2,
): { line: string; area: string } {
  if (values.length === 0) {
    return { line: '', area: '' };
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min;
  const usable = height - padding * 2;
  const stepX = values.length > 1 ? width / (values.length - 1) : 0;
  const points = (values.length > 1 ? values : [values[0], values[0]]).map((v, i) => {
    const x = values.length > 1 ? i * stepX : i * width;
    const y = span === 0 ? height / 2 : padding + usable - ((v - min) / span) * usable;
    return [round(x), round(y)] as const;
  });
  const line = points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x},${y}`).join('');
  const area = `${line}L${points[points.length - 1][0]},${height}L${points[0][0]},${height}Z`;
  return { line, area };
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
