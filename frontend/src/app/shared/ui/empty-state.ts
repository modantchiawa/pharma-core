import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export type EmptyIllustration =
  'box' | 'calendar' | 'cart' | 'check' | 'delivery' | 'people' | 'receipt' | 'search';

/**
 * Empty list with a light illustration drawn in code from the theme tokens (follows the brand,
 * the dark mode and the high contrast), the message and, when useful, the main action.
 *
 *   <app-empty-state illustration="box"><p>Aucun produit</p><button class="btn …">…</button></app-empty-state>
 */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'empty-state' },
  template: `
    <svg class="empty-ill" viewBox="0 0 120 88" width="120" height="88" aria-hidden="true">
      <ellipse class="ill-bg" cx="60" cy="46" rx="46" ry="34" />
      <ellipse class="ill-shadow" cx="60" cy="79" rx="24" ry="2.5" />
      <circle class="ill-dot" cx="17" cy="22" r="2.2" />
      <circle class="ill-dot-muted" cx="104" cy="26" r="1.6" />
      <path class="ill-line" d="M101 63h6M104 60v6" />
      <g class="ill-object">
        @switch (illustration()) {
          @case ('cart') {
            <path class="ill-surface" d="M34 38h52l-6 28H40z" />
            <path class="ill-line" d="M44 38l9-15M76 38l-9-15M50 46v12M60 46v12M70 46v12" />
            <rect class="ill-surface" x="70" y="11" width="28" height="19" rx="3" />
            <path class="ill-line" d="M75 16v9M78.5 16v9M83 16v9M86.5 16v9M91 16v9" />
            <path class="ill-accent" pathLength="1" d="M65 20.5h38" />
          }
          @case ('search') {
            <rect class="ill-surface" x="34" y="16" width="40" height="54" rx="5" />
            <path class="ill-line" d="M42 28h24M42 36h17M42 44h21M42 52h12" />
            <circle class="ill-surface ill-accent-stroke" cx="78" cy="54" r="12" />
            <path class="ill-accent ill-thick" pathLength="1" d="M87 63l9 9" />
          }
          @case ('box') {
            <path class="ill-surface" d="M36 38l24 11v25l-24-11z" />
            <path class="ill-surface-2" d="M60 49l24-11v25l-24 11z" />
            <path class="ill-surface-3" d="M36 38l24-11 24 11-24 11z" />
            <path class="ill-accent" pathLength="1" d="M48 32.5l24 11v8" />
          }
          @case ('delivery') {
            <rect class="ill-surface" x="30" y="32" width="38" height="28" rx="3" />
            <path class="ill-surface-2" d="M68 41h11l9 10v9H68z" />
            <path class="ill-line" d="M16 38h9M12 46h12M18 54h7" />
            <circle class="ill-surface ill-accent-stroke" cx="42" cy="62" r="5" />
            <circle class="ill-surface ill-accent-stroke" cx="78" cy="62" r="5" />
            <path class="ill-accent" pathLength="1" d="M38 44h22" />
          }
          @case ('people') {
            <circle class="ill-surface-2" cx="75" cy="38" r="7.5" />
            <path class="ill-surface-2" d="M63 70c0-9 5.5-14 12-14s12 5 12 14z" />
            <circle class="ill-surface" cx="52" cy="35" r="9.5" />
            <path class="ill-surface" d="M35 72c0-11 7.5-18 17-18s17 7 17 18z" />
            <path class="ill-accent" pathLength="1" d="M82 22a12 12 0 0 1 8 10" />
          }
          @case ('receipt') {
            <path class="ill-surface" d="M42 14h36v58l-6-4-6 4-6-4-6 4-6-4-6 4z" />
            <path class="ill-line" d="M50 26h20M50 34h13M50 42h20" />
            <path class="ill-accent ill-thick" pathLength="1" d="M50 55h20" />
          }
          @case ('check') {
            <path class="ill-surface" d="M60 14l23 8v19c0 15-10 27-23 31-13-4-23-16-23-31V22z" />
            <path class="ill-accent ill-thick" pathLength="1" d="M50 43l7 7 14-14" />
          }
          @case ('calendar') {
            <rect class="ill-surface" x="34" y="22" width="52" height="48" rx="6" />
            <path class="ill-line" d="M34 35h52M47 16v11M73 16v11" />
            <path
              class="ill-line"
              d="M45 45h.01M55 45h.01M65 45h.01M75 45h.01M45 56h.01M55 56h.01"
            />
            <path class="ill-accent ill-thick" pathLength="1" d="M63 57l4 4 9-9" />
          }
        }
      </g>
    </svg>
    <ng-content />
  `,
  styles: `
    .empty-ill {
      margin-bottom: 0.25rem;
      overflow: visible;
    }
    .empty-ill .ill-bg {
      fill: var(--brand-softer);
    }
    .empty-ill .ill-shadow {
      fill: color-mix(in oklab, var(--text) 10%, transparent);
    }
    .empty-ill .ill-dot {
      fill: var(--brand-primary);
      opacity: 0.55;
    }
    .empty-ill .ill-dot-muted {
      fill: var(--border-strong);
    }
    .empty-ill :is(.ill-surface, .ill-surface-2, .ill-surface-3, .ill-line, .ill-accent) {
      stroke-width: 1.6;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
    .empty-ill :is(.ill-surface, .ill-surface-2, .ill-surface-3) {
      stroke: color-mix(in oklab, var(--text-subtle) 75%, var(--surface));
    }
    .empty-ill .ill-surface {
      fill: var(--surface);
    }
    .empty-ill .ill-surface-2 {
      fill: var(--surface-2);
    }
    .empty-ill .ill-surface-3 {
      fill: var(--surface-3);
    }
    .empty-ill .ill-line {
      fill: none;
      stroke: var(--text-subtle);
    }
    .empty-ill .ill-accent {
      fill: none;
      stroke: var(--brand-primary);
      stroke-dasharray: 1;
      animation: draw var(--motion-long) var(--ease-decelerate) 260ms backwards;
    }
    .empty-ill .ill-accent-stroke {
      stroke: var(--brand-primary);
    }
    .empty-ill .ill-thick {
      stroke-width: 3;
    }
    .empty-ill .ill-object {
      transform-box: fill-box;
      transform-origin: 50% 100%;
      animation: ill-rise var(--motion-long) var(--ease-decelerate) 60ms backwards;
    }
  `,
})
export class EmptyState {
  readonly illustration = input<EmptyIllustration>('search');
}
