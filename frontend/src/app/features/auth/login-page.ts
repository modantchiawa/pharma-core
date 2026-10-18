import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { AuthService, Realm } from '../../core/auth/auth.service';
import { BrandingService } from '../../core/branding/branding.service';

@Component({
  selector: 'app-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="flex min-h-screen items-center justify-center bg-brand-dark p-4">
      <section class="w-full max-w-sm space-y-4 rounded-xl bg-white p-6 text-center shadow-xl">
        @if (branding.logoUrl(); as logo) {
          <img [src]="logo" alt="" class="mx-auto max-h-20" />
        }
        <h1 class="text-xl font-bold text-brand">{{ branding.branding().displayName }}</h1>
        <button
          type="button"
          class="w-full rounded-lg bg-brand py-3 font-semibold text-on-brand"
          (click)="login('pharmacy')"
        >
          Connexion pharmacie
        </button>
        <button
          type="button"
          class="w-full text-sm text-slate-500 underline"
          (click)="login('platform')"
        >
          Console éditeur
        </button>
      </section>
    </main>
  `,
})
export class LoginPage {
  protected readonly branding = inject(BrandingService);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);

  constructor() {
    // Pharmacy subdomain (e.g. lafayette.pharmacore.io) drives the pre-login identity.
    const slug = location.hostname.split('.')[0];
    if (slug && slug !== 'localhost' && slug !== 'app') {
      void this.branding.loadPublic(slug);
    }
  }

  protected login(realm: Realm): Promise<void> {
    return this.auth.login(realm, this.route.snapshot.queryParamMap.get('returnUrl') ?? '/');
  }
}
