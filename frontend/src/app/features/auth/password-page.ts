import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { APP_ENVIRONMENT } from '../../core/config';

/** Password change (forced after a temporary password) and PIN definition. */
@Component({
  selector: 'app-password-page',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-md space-y-6 p-4">
      <form class="space-y-3 rounded-lg border bg-white p-4" (ngSubmit)="changePassword()">
        <h2 class="text-lg font-semibold">Mot de passe</h2>
        @if (auth.mustChangePassword()) {
          <p class="rounded bg-amber-100 px-3 py-2 text-sm text-amber-900">
            Votre mot de passe est temporaire : choisissez-en un nouveau (8 caractères minimum,
            lettres et chiffres).
          </p>
        }
        <label class="block text-sm"
          >Mot de passe actuel<input
            type="password"
            class="field"
            [(ngModel)]="current"
            name="current"
            autocomplete="current-password"
            required
        /></label>
        <label class="block text-sm"
          >Nouveau mot de passe<input
            type="password"
            class="field"
            [(ngModel)]="next"
            name="next"
            autocomplete="new-password"
            required
            minlength="8"
        /></label>
        <label class="block text-sm"
          >Confirmation<input
            type="password"
            class="field"
            [(ngModel)]="confirm"
            name="confirm"
            autocomplete="new-password"
            required
        /></label>
        <button
          type="submit"
          class="rounded bg-brand px-4 py-2 text-on-brand"
          [disabled]="next !== confirm || next.length < 8"
        >
          Enregistrer
        </button>
      </form>

      <form class="space-y-3 rounded-lg border bg-white p-4" (ngSubmit)="setPin()">
        <h2 class="text-lg font-semibold">Code PIN (bascule rapide en caisse)</h2>
        <label class="block text-sm"
          >Mot de passe<input
            type="password"
            class="field"
            [(ngModel)]="pinPassword"
            name="pinPassword"
            autocomplete="current-password"
            required
        /></label>
        <label class="block text-sm"
          >PIN (4 à 6 chiffres)<input
            type="password"
            inputmode="numeric"
            maxlength="6"
            class="field"
            [(ngModel)]="pin"
            name="pin"
            required
        /></label>
        <button type="submit" class="rounded bg-brand px-4 py-2 text-on-brand">
          Définir le PIN
        </button>
      </form>
      @if (status(); as text) {
        <p role="status" class="text-sm">{{ text }}</p>
      }
    </div>
  `,
  styles: `
    .field {
      display: block;
      width: 100%;
      margin-top: 0.25rem;
      border: 1px solid #cbd5e1;
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
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
