import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { GalenicShape, galenicShape, thumbHue, thumbInitial } from '../product-thumb';
import { ProductImageService } from './product-image.service';

/**
 * Square thumbnail of a product: its photo when the pharmacy took one, otherwise a placeholder
 * tinted from the name with the initial and the dosage form. The photo loads when the thumbnail
 * comes into view and stays cached (offline included).
 *
 *   <app-product-thumb [productId]="p.productId" [version]="p.imageVersion" [name]="p.designation" />
 */
@Component({
  selector: 'app-product-thumb',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'product-thumb',
    role: 'img',
    '[attr.aria-label]': 'label()',
    '[style.--thumb-size.px]': 'size()',
    '[style.--thumb-hue]': 'hue()',
    '[class.has-photo]': '!!src()',
  },
  template: `
    @if (src(); as url) {
      <img
        [src]="url"
        alt=""
        loading="lazy"
        decoding="async"
        [attr.width]="size()"
        [attr.height]="size()"
        (error)="src.set(null)"
      />
    } @else {
      <span class="thumb-initial">{{ initial() }}</span>
      @if (shape(); as s) {
        <svg class="thumb-shape" viewBox="-16 -16 32 32" aria-hidden="true">
          @switch (s) {
            @case ('tablet') {
              <circle r="10" />
              <line x1="-6" y1="0" x2="6" y2="0" />
            }
            @case ('capsule') {
              <g transform="rotate(-40)">
                <rect x="-13" y="-6" width="26" height="12" rx="6" />
                <line x1="0" y1="-6" x2="0" y2="6" />
              </g>
            }
            @case ('liquid') {
              <path
                d="M-5 -13 h10 v5 l3 4 v15 a2 2 0 0 1 -2 2 h-12 a2 2 0 0 1 -2 -2 v-15 l3 -4 z"
              />
              <line x1="-6" y1="3" x2="6" y2="3" />
            }
            @case ('injection') {
              <g transform="rotate(45)">
                <rect x="-4" y="-10" width="8" height="18" rx="1.5" />
                <line x1="0" y1="8" x2="0" y2="14" />
                <line x1="-6" y1="-10" x2="6" y2="-10" />
              </g>
            }
            @case ('inhaler') {
              <path d="M-6 -13 h8 v16 h7 v8 h-15 z" />
            }
            @case ('topical') {
              <g transform="rotate(-35)">
                <path d="M-14 -5 h20 l5 2 v6 l-5 2 h-20 z" />
                <line x1="11" y1="-2" x2="14" y2="-2" />
              </g>
            }
            @case ('sachet') {
              <rect x="-10" y="-12" width="20" height="24" rx="2" />
              <line x1="-10" y1="-7" x2="10" y2="-7" />
            }
            @case ('device') {
              <rect x="-11" y="-11" width="22" height="22" rx="5" />
              <line x1="0" y1="-5" x2="0" y2="5" />
              <line x1="-5" y1="0" x2="5" y2="0" />
            }
          }
        </svg>
      }
    }
  `,
  styles: `
    :host {
      position: relative;
      display: inline-flex;
      flex-shrink: 0;
      align-items: center;
      justify-content: center;
      width: var(--thumb-size);
      height: var(--thumb-size);
      overflow: hidden;
      border-radius: min(calc(var(--thumb-size) * 0.22), 1rem);
      background: oklch(0.95 0.035 var(--thumb-hue));
      color: oklch(0.47 0.11 var(--thumb-hue));
      box-shadow: inset 0 0 0 1px color-mix(in oklab, currentColor 14%, transparent);
      user-select: none;
    }
    :host-context([data-theme='dark']) {
      background: oklch(0.3 0.045 var(--thumb-hue));
      color: oklch(0.82 0.09 var(--thumb-hue));
    }
    /* After the dark tint, same specificity: a photo always sits on the neutral surface */
    :host(.has-photo) {
      background: var(--surface-2);
      box-shadow: inset 0 0 0 1px var(--border);
    }
    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
    .thumb-initial {
      font-size: calc(var(--thumb-size) * 0.42);
      font-weight: 700;
      line-height: 1;
      letter-spacing: -0.02em;
    }
    .thumb-shape {
      position: absolute;
      right: calc(var(--thumb-size) * 0.06);
      bottom: calc(var(--thumb-size) * 0.06);
      width: calc(var(--thumb-size) * 0.36);
      height: calc(var(--thumb-size) * 0.36);
      fill: none;
      stroke: currentColor;
      stroke-width: 2.6;
      stroke-linecap: round;
      stroke-linejoin: round;
      opacity: 0.7;
    }
  `,
})
export class ProductThumb {
  readonly productId = input.required<string>();
  readonly version = input<string | null | undefined>(null);
  readonly name = input.required<string>();
  /** Dosage form from the catalog, when the caller has it. */
  readonly form = input<string | null | undefined>(null);
  readonly size = input(40);

  private readonly images = inject(ProductImageService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly src = signal<string | null>(null);
  protected readonly hue = computed(() => thumbHue(this.name()));
  protected readonly initial = computed(() => thumbInitial(this.name()));
  protected readonly shape = computed<GalenicShape | null>(() =>
    galenicShape(this.name(), this.form()),
  );
  protected readonly label = computed(() =>
    this.src() ? `Photo de ${this.name()}` : `${this.name()} (sans photo)`,
  );
  private readonly effectiveVersion = computed(() =>
    this.images.versionOf(this.productId(), this.version()),
  );

  constructor() {
    effect((onCleanup) => {
      const productId = this.productId();
      const version = this.effectiveVersion();
      const ready = version ? this.images.cached(productId, version) : null;
      this.src.set(ready);
      if (!version || ready) {
        return;
      }
      let active = true;
      const stop = this.images.whenVisible(this.host.nativeElement, () => {
        void this.images.load(productId, version).then((url) => {
          if (active) {
            this.src.set(url);
          }
        });
      });
      onCleanup(() => {
        active = false;
        stop();
      });
    });
  }
}
