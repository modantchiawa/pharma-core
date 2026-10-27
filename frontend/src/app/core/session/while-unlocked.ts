import { Directive, TemplateRef, ViewContainerRef, effect, inject } from '@angular/core';
import { IdleService } from './idle.service';

/**
 * Renders its element only while the terminal is unlocked: locked, the page is destroyed, not
 * hidden, so that nothing of it is left in the DOM. What must survive the lock (the counter's
 * basket) lives in the stores; the page is rebuilt from them once unlocked.
 */
@Directive({ selector: '[appWhileUnlocked]' })
export class WhileUnlocked {
  constructor() {
    const idle = inject(IdleService);
    const template = inject(TemplateRef);
    const container = inject(ViewContainerRef);
    effect(() => {
      if (idle.locked()) {
        container.clear();
      } else if (container.length === 0) {
        container.createEmbeddedView(template);
      }
    });
  }
}
