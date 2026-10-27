import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ProductPlace, locationChipLabel, locationTitle } from '../storage-location';
import { Icon } from './icon';

/**
 * Where a product is stored, as a compact chip ("A3 · case 12") with the full location name in
 * the tooltip. Fridge and safe stand out: the product is not on an open shelf.
 *
 *   <app-location-chip [place]="{ code: 'A3', name: 'Rayon A · étagère 3', kind: 'SHELF', detail: 'case 12' }" />
 */
@Component({
  selector: 'app-location-chip',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="chip"
      [attr.data-kind]="place().kind"
      [attr.title]="title()"
      [attr.aria-label]="'Emplacement ' + title()"
      role="img"
    >
      <app-icon name="mapPin" [size]="12" [stroke]="2" />
      <span class="truncate">{{ label() }}</span>
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
      min-width: 0;
      max-width: 100%;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      min-width: 0;
      max-width: 100%;
      height: 1.3rem;
      padding: 0 0.45rem;
      border: 1px solid var(--border);
      border-radius: 999px;
      background: var(--surface-2);
      color: var(--text-muted);
      font-size: 0.72rem;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .chip[data-kind='FRIDGE'] {
      border-color: color-mix(in oklab, var(--info) 35%, var(--border));
      background: var(--info-soft);
      color: var(--info);
    }
    .chip[data-kind='SAFE'] {
      border-color: color-mix(in oklab, var(--warning) 40%, var(--border));
      background: var(--warning-soft);
      color: var(--warning);
    }
  `,
})
export class LocationChip {
  readonly place = input.required<ProductPlace>();

  protected readonly label = computed(() =>
    locationChipLabel(this.place().code, this.place().detail),
  );
  protected readonly title = computed(() =>
    locationTitle(this.place().name, this.place().detail, this.place().kind),
  );
}
