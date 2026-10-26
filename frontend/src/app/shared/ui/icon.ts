import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Renderer2,
  effect,
  inject,
  input,
} from '@angular/core';
import { ICONS, IconName } from './icons';

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * Inline SVG icon from the Lucide set, drawn with the current text color. Built with the
 * renderer from the icon's node list (no innerHTML), so it works offline and in print.
 *
 *   <app-icon name="cart" />  <app-icon name="alert" [size]="16" [stroke]="2.25" />
 */
@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
  host: {
    class: 'inline-flex shrink-0 items-center justify-center',
    'aria-hidden': 'true',
  },
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly size = input(18);
  readonly stroke = input(1.75);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);

  constructor() {
    effect(() => {
      const node = ICONS[this.name()];
      const element = this.host.nativeElement;
      element.replaceChildren();
      const svg = this.renderer.createElement('svg', SVG_NS) as SVGElement;
      const attributes: Record<string, string> = {
        xmlns: SVG_NS,
        width: String(this.size()),
        height: String(this.size()),
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        'stroke-width': String(this.stroke()),
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
      };
      for (const [key, value] of Object.entries(attributes)) {
        this.renderer.setAttribute(svg, key, value);
      }
      const children = (node[2] ?? []) as readonly (readonly [
        string,
        Record<string, string | number>,
      ])[];
      for (const [tag, attrs] of children) {
        const child = this.renderer.createElement(tag, SVG_NS) as SVGElement;
        for (const [key, value] of Object.entries(attrs)) {
          this.renderer.setAttribute(child, key, String(value));
        }
        this.renderer.appendChild(svg, child);
      }
      this.renderer.appendChild(element, svg);
    });
  }
}
