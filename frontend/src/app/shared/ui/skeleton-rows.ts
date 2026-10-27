import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Placeholder rows while a list loads: a thumbnail, two text bars and figures, in the shape of
 * the rows to come so that the content does not jump when it arrives. Hidden from assistive
 * technologies, which get the "Chargement" status instead.
 *
 *   <app-skeleton-rows [rows]="8" [figures]="3" [thumb]="true" />
 */
@Component({
  selector: 'app-skeleton-rows',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'skeleton-rows', role: 'status' },
  template: `
    <span class="sr-only">Chargement…</span>
    @for (row of lines(); track row.index) {
      <div class="skeleton-row" aria-hidden="true" [style.--i]="row.index">
        @if (thumb()) {
          <span class="skeleton h-8 w-8 shrink-0 !rounded-[0.55rem]"></span>
        }
        <span class="flex min-w-0 flex-1 flex-col gap-1.5">
          <span class="skeleton h-3.5" [style.width.%]="row.main"></span>
          <span class="skeleton h-2.5" [style.width.%]="row.sub"></span>
        </span>
        @for (figure of figureSlots(); track figure) {
          <span
            class="skeleton hidden h-3.5 shrink-0 sm:block"
            [style.width.rem]="figure === 0 ? 5 : 3.5"
          ></span>
        }
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
      animation: fade-in var(--motion-medium) var(--ease-standard) backwards;
    }
    .skeleton-row {
      display: flex;
      align-items: center;
      gap: 0.85rem;
      padding: calc(0.8rem * var(--density)) 1rem;
      border-bottom: 1px solid var(--border);
    }
    .skeleton-row:last-child {
      border-bottom: 0;
    }
    .skeleton-row .skeleton::after {
      animation-delay: calc(var(--i, 0) * 90ms);
    }
  `,
})
export class SkeletonRows {
  readonly rows = input(6);
  readonly figures = input(2);
  readonly thumb = input(false);

  /** Varied but stable bar widths: a regular grid of identical bars reads as a pattern, not as text. */
  protected readonly lines = computed(() =>
    Array.from({ length: this.rows() }, (_, index) => ({
      index,
      main: 38 + ((index * 37) % 34),
      sub: 18 + ((index * 23) % 20),
    })),
  );
  protected readonly figureSlots = computed(() =>
    Array.from({ length: this.figures() }, (_, i) => i),
  );
}
