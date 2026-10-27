import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { sparklinePath } from './motion';

let nextId = 0;

/**
 * Small trend line in the brand color over a fading area, drawn from left to right the first
 * time it is shown (stroke offset). It takes the width it is given, down to 2.5rem.
 * Decorative: the figures sit next to it.
 */
@Component({
  selector: 'app-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true', '[style.height.px]': 'height()' },
  template: `
    <svg
      [attr.viewBox]="'0 0 ' + width() + ' ' + height()"
      width="100%"
      [attr.height]="height()"
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient [attr.id]="gradient" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="currentColor" stop-opacity="0.22" />
          <stop offset="1" stop-color="currentColor" stop-opacity="0" />
        </linearGradient>
      </defs>
      <path class="area" [attr.d]="paths().area" [attr.fill]="'url(#' + gradient + ')'" />
      <path class="line" pathLength="1" [attr.d]="paths().line" />
    </svg>
  `,
  styles: `
    :host {
      display: block;
      flex: 0 1 var(--sparkline-width, 6rem);
      min-width: 2.5rem;
      color: var(--brand-primary);
    }
    svg {
      display: block;
      overflow: visible;
    }
    .line {
      fill: none;
      stroke: currentColor;
      stroke-width: 1.75;
      stroke-linecap: round;
      stroke-linejoin: round;
      vector-effect: non-scaling-stroke;
      stroke-dasharray: 1;
      animation: draw 900ms var(--ease-decelerate) 200ms backwards;
    }
    .area {
      animation: fade-in 700ms var(--ease-standard) 500ms backwards;
    }
  `,
})
export class Sparkline {
  readonly values = input.required<readonly number[]>();
  readonly width = input(96);
  readonly height = input(28);

  protected readonly gradient = `sparkline-${nextId++}`;
  protected readonly paths = computed(() =>
    sparklinePath(this.values(), this.width(), this.height(), 3),
  );
}
