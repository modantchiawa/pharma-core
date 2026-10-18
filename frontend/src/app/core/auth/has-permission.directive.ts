import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { PermissionService } from './permission.service';

/**
 * Structural directive: renders its template only when the user holds at least one of the
 * given permissions. Usage: {@code <button *appHasPermission="'SALE_CANCEL'">}.
 */
@Directive({ selector: '[appHasPermission]' })
export class HasPermissionDirective {
  private readonly permissions = inject(PermissionService);
  private readonly template = inject(TemplateRef<unknown>);
  private readonly container = inject(ViewContainerRef);
  private rendered = false;

  readonly appHasPermission = input.required<string | string[]>();

  constructor() {
    effect(() => {
      const required = this.appHasPermission();
      const allowed = this.permissions.hasAny(...(Array.isArray(required) ? required : [required]));
      if (allowed && !this.rendered) {
        this.container.createEmbeddedView(this.template);
        this.rendered = true;
      } else if (!allowed && this.rendered) {
        this.container.clear();
        this.rendered = false;
      }
    });
  }
}
