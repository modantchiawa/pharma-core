import { Signal, effect, signal, untracked } from '@angular/core';
import { DURATION, prefersReducedMotion } from './motion';

/** An item of a displayed list, and whether it is playing its exit animation. */
export interface Exiting<T> {
  item: T;
  leaving: boolean;
}

/**
 * Next state of a displayed list: items still in `current` take their fresh value, items gone
 * from it stay in place marked as leaving (until their exit ends), new ones are added at the end
 * in their order. Returns the keys that just started leaving.
 */
export function mergeExits<T, K>(
  previous: readonly Exiting<T>[],
  current: readonly T[],
  key: (item: T) => K,
): { list: Exiting<T>[]; left: K[] } {
  const fresh = new Map(current.map((item) => [key(item), item]));
  const left: K[] = [];
  const list = previous.map((entry) => {
    const id = key(entry.item);
    const now = fresh.get(id);
    if (fresh.has(id)) {
      fresh.delete(id);
      return { item: now as T, leaving: false };
    }
    if (!entry.leaving) {
      left.push(id);
    }
    return entry.leaving ? entry : { item: entry.item, leaving: true };
  });
  for (const item of fresh.values()) {
    list.push({ item, leaving: false });
  }
  return { list, left };
}

/**
 * The source list as displayed, with removed items kept for the length of their exit animation
 * (CSS class bound to `leaving`). Lighter than the framework's leave animations, which would
 * weigh on the first screen. Call it in an injection context.
 *
 *   protected readonly lines = withExits(() => this.cart.lines(), (l) => l.productId);
 *   @for (entry of lines(); track entry.item.productId) { <tr [class.row-leave]="entry.leaving"> }
 */
export function withExits<T, K>(
  source: () => readonly T[],
  key: (item: T) => K,
  duration: number = DURATION.medium,
): Signal<Exiting<T>[]> {
  const shown = signal<Exiting<T>[]>([]);
  effect(() => {
    const current = source();
    untracked(() => {
      const { list, left } = mergeExits(shown(), current, key);
      shown.set(list);
      if (left.length > 0) {
        setTimeout(
          () =>
            shown.update((entries) =>
              entries.filter((entry) => !(entry.leaving && left.includes(key(entry.item)))),
            ),
          prefersReducedMotion() ? 0 : duration,
        );
      }
    });
  });
  return shown.asReadonly();
}
