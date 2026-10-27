import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { MoneyPipe } from '../money.pipe';
import { countDuration, tween } from './motion';

const integers = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });

/**
 * A figure that rolls to its new value (amount in minor units or count). The rolling digits are
 * hidden from assistive technologies, which only get the final value — announced politely
 * when `live` is set (POS total), read on demand otherwise (dashboard).
 *
 *   <app-animated-number [value]="cart.netCents()" format="money" [live]="true" />
 */
@Component({
  selector: 'app-animated-number',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span aria-hidden="true" class="tabular-nums">{{ shown() }}</span
    ><span class="sr-only" [attr.aria-live]="live() ? 'polite' : null">{{ final() }}</span>`,
})
export class AnimatedNumber {
  readonly value = input.required<number>();
  readonly format = input<'money' | 'integer'>('money');
  /** Value the first display counts from; by default the figure appears as is. */
  readonly from = input<number | null>(null);
  readonly minDuration = input(160);
  readonly maxDuration = input(320);
  readonly live = input(false);

  private readonly money = new MoneyPipe();
  private readonly current = signal<number | null>(null);
  protected readonly shown = computed(() => this.text(this.current() ?? this.value()));
  protected readonly final = computed(() => this.text(this.value()));

  constructor() {
    let stop: () => void = () => undefined;
    inject(DestroyRef).onDestroy(() => stop());
    effect(() => {
      const target = this.value();
      untracked(() => {
        const start = this.current() ?? this.from() ?? target;
        stop();
        stop = tween(
          start,
          target,
          countDuration(start, target, this.minDuration(), this.maxDuration()),
          (value) => this.current.set(value),
        );
      });
    });
  }

  private text(value: number): string {
    return this.format() === 'money' ? this.money.transform(value) : integers.format(value);
  }
}
