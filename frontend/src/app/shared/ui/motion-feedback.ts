import { Directive, ElementRef, effect, inject, input } from '@angular/core';
import { DURATION, EASING, prefersReducedMotion } from './motion';

export type FeedbackKind = 'flash' | 'pulse' | 'pop' | 'ring';

const KEYFRAMES: Record<FeedbackKind, Keyframe[]> = {
  /** Brand wash fading out (on the ::before of a .flash-surface): a line just added or changed. */
  flash: [{ opacity: 1 }, { opacity: 1, offset: 0.2 }, { opacity: 0 }],
  /** Short swell: a quantity that changed. */
  pulse: [
    { transform: 'scale(1)' },
    { transform: 'scale(1.16)', offset: 0.35 },
    { transform: 'scale(1)' },
  ],
  /** Badge counter bump. */
  pop: [
    { transform: 'scale(1)' },
    { transform: 'scale(1.35)', offset: 0.4 },
    { transform: 'scale(1)' },
  ],
  /** Bell swinging from its top: a new alert. */
  ring: [
    { transform: 'rotate(0)' },
    { transform: 'rotate(16deg)', offset: 0.15 },
    { transform: 'rotate(-13deg)', offset: 0.32 },
    { transform: 'rotate(9deg)', offset: 0.5 },
    { transform: 'rotate(-5deg)', offset: 0.68 },
    { transform: 'rotate(2deg)', offset: 0.84 },
    { transform: 'rotate(0)' },
  ],
};

const TIMING: Record<FeedbackKind, number> = {
  flash: 1000,
  pulse: DURATION.long,
  pop: DURATION.long,
  ring: 900,
};

/**
 * Plays a short feedback animation on the element each time `appFeedback` changes — and on
 * the first display too with `feedbackOnInit`. Web Animations API, no style left behind once it
 * ends, nothing played with reduced motion.
 *
 *   <tr class="flash-surface" [appFeedback]="line.quantity" [feedbackOnInit]="true">…</tr>
 *   <input [appFeedback]="line.quantity" feedbackKind="pulse" />
 */
@Directive({ selector: '[appFeedback]' })
export class MotionFeedback {
  readonly appFeedback = input<unknown>();
  readonly feedbackKind = input<FeedbackKind>('flash');
  readonly feedbackOnInit = input(false);

  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  constructor() {
    let first = true;
    let previous: unknown;
    effect(() => {
      const value = this.appFeedback();
      const changed = !first && !Object.is(value, previous);
      const initial = first && this.feedbackOnInit();
      first = false;
      previous = value;
      if (changed || initial) {
        play(this.element, this.feedbackKind());
      }
    });
  }
}

/** Same animations for code that has no template binding (bell, badges). */
export function play(element: Element, kind: FeedbackKind): void {
  if (prefersReducedMotion() || typeof element.animate !== 'function') {
    return;
  }
  const options: KeyframeAnimationOptions = {
    duration: TIMING[kind],
    easing: kind === 'flash' ? EASING.standard : EASING.emphasized,
  };
  if (kind !== 'flash') {
    element.animate(KEYFRAMES[kind], options);
    return;
  }
  // A table row is washed cell by cell (a row cannot carry the overlay itself)
  const surfaces = element.tagName === 'TR' ? Array.from(element.children) : [element];
  for (const surface of surfaces) {
    surface.animate(KEYFRAMES.flash, { ...options, pseudoElement: '::before' });
  }
}
