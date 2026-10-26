import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { Icon } from '../../shared/ui/icon';

/** Password change (forced after a temporary password) and PIN definition. */
@Component({
  selector: 'app-password-page',
  imports: [FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <div class="max-w-4xl">
        <header class="page-header">
          <div>
            <h1 class="page-title">Mot de passe et code PIN</h1>
            <p class="page-subtitle">Sécurité de votre compte et bascule rapide entre vendeurs.</p>
          </div>
        </header>

        @if (status(); as text) {
          <p role="status" class="alert alert-info mb-6">
            <app-icon name="info" [size]="18" />
            <span>{{ text }}</span>
          </p>
        }

        <div class="space-y-8">
          <form class="settings-row" (ngSubmit)="changePassword()">
            <div>
              <h2 class="flex items-center gap-2 text-[0.95rem] font-semibold">
                <app-icon name="lock" [size]="16" class="text-muted" />
                Mot de passe
              </h2>
              <p class="mt-1 text-[0.8125rem] leading-relaxed text-muted">
                8 caractères minimum, lettres et chiffres. Vous serez reconnecté avec le nouveau mot
                de passe.
              </p>
            </div>
            <div class="card">
              <div class="card-body space-y-4">
                @if (auth.mustChangePassword()) {
                  <p class="alert alert-warning">
                    <app-icon name="alert" [size]="18" />
                    <span>
                      Votre mot de passe est temporaire : choisissez-en un nouveau (8 caractères
                      minimum, lettres et chiffres).
                    </span>
                  </p>
                }
                <label class="block"
                  ><span class="label">Mot de passe actuel</span
                  ><input
                    type="password"
                    class="input"
                    [(ngModel)]="current"
                    name="current"
                    autocomplete="current-password"
                    required
                /></label>
                <div class="grid gap-4 sm:grid-cols-2">
                  <label class="block"
                    ><span class="label">Nouveau mot de passe</span
                    ><input
                      type="password"
                      class="input"
                      [(ngModel)]="next"
                      name="next"
                      autocomplete="new-password"
                      required
                      minlength="8"
                  /></label>
                  <label class="block"
                    ><span class="label">Confirmation</span
                    ><input
                      type="password"
                      class="input"
                      [(ngModel)]="confirm"
                      name="confirm"
                      autocomplete="new-password"
                      required
                  /></label>
                </div>
                @if (next.length > 0) {
                  <ul class="flex flex-wrap gap-2 text-xs">
                    <li class="badge" [class.badge-success]="next.length >= 8">
                      <app-icon [name]="next.length >= 8 ? 'check' : 'minus'" [size]="12" />
                      8 caractères
                    </li>
                    <li
                      class="badge"
                      [class.badge-success]="confirm.length > 0 && next === confirm"
                    >
                      <app-icon
                        [name]="confirm.length > 0 && next === confirm ? 'check' : 'minus'"
                        [size]="12"
                      />
                      Confirmation identique
                    </li>
                  </ul>
                }
              </div>
              <div class="card-footer">
                <button
                  type="submit"
                  class="btn btn-primary"
                  [disabled]="next !== confirm || next.length < 8"
                >
                  <app-icon name="check" [size]="16" />
                  Enregistrer
                </button>
              </div>
            </div>
          </form>

          <form class="settings-row" (ngSubmit)="setPin()">
            <div>
              <h2 class="flex items-center gap-2 text-[0.95rem] font-semibold">
                <app-icon name="zap" [size]="16" class="text-muted" />
                Code PIN (bascule rapide en caisse)
              </h2>
              <p class="mt-1 text-[0.8125rem] leading-relaxed text-muted">
                Pour changer de vendeur sur un poste déjà connecté sans ressaisir de mot de passe.
              </p>
            </div>
            <div class="card">
              <div class="card-body grid gap-4 sm:grid-cols-2">
                <label class="block"
                  ><span class="label">Mot de passe</span
                  ><input
                    type="password"
                    class="input"
                    [(ngModel)]="pinPassword"
                    name="pinPassword"
                    autocomplete="current-password"
                    required
                /></label>
                <label class="block"
                  ><span class="label">PIN (4 à 6 chiffres)</span
                  ><input
                    type="password"
                    inputmode="numeric"
                    maxlength="6"
                    class="input font-mono tracking-[0.3em]"
                    [(ngModel)]="pin"
                    name="pin"
                    required
                /></label>
              </div>
              <div class="card-footer">
                <button type="submit" class="btn btn-primary">
                  <app-icon name="key" [size]="16" />
                  Définir le PIN
                </button>
              </div>
            </div>
          </form>
        </div>
      </div>
    </div>
  `,
  styles: `
    .settings-row {
      display: grid;
      gap: 1rem;
    }
    @media (min-width: 768px) {
      .settings-row {
        grid-template-columns: 15rem minmax(0, 1fr);
        gap: 2rem;
      }
    }
    .card-footer {
      display: flex;
      justify-content: flex-end;
      gap: 0.5rem;
      padding: 0.75rem calc(1.25rem * var(--density));
      border-top: 1px solid var(--border);
      background: var(--surface-2);
      border-radius: 0 0 var(--radius-lg) var(--radius-lg);
    }
  `,
})
export class PasswordPage {
  protected readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly router = inject(Router);
  protected readonly status = signal<string | null>(null);
  protected current = '';
  protected next = '';
  protected confirm = '';
  protected pinPassword = '';
  protected pin = '';

  protected async changePassword(): Promise<void> {
    try {
      await firstValueFrom(
        this.http.put(`${this.env.apiUrl}/v1/me/password`, {
          currentPassword: this.current,
          newPassword: this.next,
        }),
      );
      this.auth.passwordChanged();
      this.status.set('Mot de passe modifié : reconnectez-vous avec le nouveau mot de passe.');
      await this.auth.logout();
      await this.router.navigateByUrl('/login');
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Modification refusée',
      );
    }
  }

  protected async setPin(): Promise<void> {
    try {
      await firstValueFrom(
        this.http.put(`${this.env.apiUrl}/v1/me/pin`, {
          password: this.pinPassword,
          pin: this.pin,
        }),
      );
      this.status.set('Code PIN enregistré.');
      this.pin = '';
      this.pinPassword = '';
    } catch (error) {
      this.status.set((error as { error?: { detail?: string } }).error?.detail ?? 'PIN refusé');
    }
  }
}
