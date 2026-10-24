import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { PermissionService } from '../../core/auth/permission.service';

@Component({
  selector: 'app-auth-callback-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="p-8 text-center">{{ error() ?? 'Connexion en cours…' }}</p>`,
})
export class AuthCallbackPage {
  protected readonly error = signal<string | null>(null);

  constructor() {
    const auth = inject(AuthService);
    const permissions = inject(PermissionService);
    const router = inject(Router);
    auth
      .completePlatformLogin()
      .then(async (returnUrl) => {
        permissions.clear();
        await router.navigateByUrl(auth.isPlatformOperator() ? returnUrl : '/login');
      })
      .catch(() => this.error.set('Échec de la connexion, veuillez réessayer.'));
  }
}
