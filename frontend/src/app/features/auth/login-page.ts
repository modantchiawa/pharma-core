import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { NodeInfoService } from '../../core/api/node-info.service';
import { AuthService } from '../../core/auth/auth.service';
import { BrandingService } from '../../core/branding/branding.service';
import { SyncOrchestratorService } from '../../core/offline/sync-orchestrator.service';
import { TerminalService } from '../../core/offline/terminal.service';

/**
 * Sign-in without Internet: credentials are checked by the local hub (or the cloud for
 * pharmacies without hub). Once a password login has bound the terminal, colleagues switch in
 * with their PIN in two taps.
 */
@Component({
  selector: 'app-login-page',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="flex min-h-screen items-center justify-center bg-brand-dark p-4">
      <section class="w-full max-w-sm space-y-4 rounded-xl bg-white p-6 shadow-xl">
        <div class="text-center">
          @if (branding.logoUrl(); as logo) {
            <img [src]="logo" alt="" class="mx-auto max-h-20" />
          }
          <h1 class="text-xl font-bold text-brand">{{ branding.branding().displayName }}</h1>
          <p class="text-xs text-slate-500">
            Poste {{ terminal.terminalId }} · {{ node.isHub() ? 'serveur local' : 'cloud' }}
          </p>
        </div>

        @if (mode() === 'pin' && knownUsers().length > 0) {
          <form class="space-y-3" (ngSubmit)="pinLogin()">
            <label class="block text-sm"
              >Utilisateur
              <select class="mt-1 w-full rounded border px-3 py-2" [(ngModel)]="email" name="email">
                @for (u of knownUsers(); track u.email) {
                  <option [value]="u.email">{{ u.name }}</option>
                }
              </select>
            </label>
            <label class="block text-sm"
              >Code PIN
              <input
                type="password"
                inputmode="numeric"
                autocomplete="off"
                maxlength="6"
                class="mt-1 w-full rounded border px-3 py-2 text-center text-2xl tracking-[0.5em]"
                [(ngModel)]="pin"
                name="pin"
                autofocus
              />
            </label>
            <button
              type="submit"
              class="w-full rounded-lg bg-brand py-3 font-semibold text-on-brand"
              [disabled]="busy()"
            >
              Entrer
            </button>
            <button
              type="button"
              class="w-full text-sm text-slate-500 underline"
              (click)="mode.set('password')"
            >
              Se connecter avec un mot de passe
            </button>
          </form>
        } @else {
          <form class="space-y-3" (ngSubmit)="passwordLogin()">
            @if (!node.isHub()) {
              <label class="block text-sm"
                >Identifiant de la pharmacie
                <input
                  class="mt-1 w-full rounded border px-3 py-2"
                  [(ngModel)]="pharmacy"
                  name="pharmacy"
                  autocomplete="organization"
                />
              </label>
            }
            <label class="block text-sm"
              >E-mail
              <input
                type="email"
                class="mt-1 w-full rounded border px-3 py-2"
                [(ngModel)]="email"
                name="email"
                autocomplete="username"
                required
              />
            </label>
            <label class="block text-sm"
              >Mot de passe
              <input
                type="password"
                class="mt-1 w-full rounded border px-3 py-2"
                [(ngModel)]="password"
                name="password"
                autocomplete="current-password"
                required
              />
            </label>
            <button
              type="submit"
              class="w-full rounded-lg bg-brand py-3 font-semibold text-on-brand"
              [disabled]="busy()"
            >
              Connexion
            </button>
            @if (auth.terminalBound() && knownUsers().length > 0) {
              <button
                type="button"
                class="w-full text-sm text-slate-500 underline"
                (click)="mode.set('pin')"
              >
                Bascule rapide par code PIN
              </button>
            }
          </form>
        }

        @if (error(); as text) {
          <p class="rounded bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">{{ text }}</p>
        }
        <button
          type="button"
          class="w-full text-xs text-slate-400 underline"
          (click)="auth.loginPlatform()"
        >
          Console éditeur
        </button>
      </section>
    </main>
  `,
})
export class LoginPage {
  protected readonly branding = inject(BrandingService);
  protected readonly auth = inject(AuthService);
  protected readonly node = inject(NodeInfoService);
  protected readonly terminal = inject(TerminalService);
  private readonly sync = inject(SyncOrchestratorService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly knownUsers = signal(this.auth.knownUsers());
  protected readonly mode = signal<'pin' | 'password'>(
    this.auth.terminalBound() && this.auth.knownUsers().length > 0 ? 'pin' : 'password',
  );
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected pharmacy = this.auth.lastPharmacy() ?? '';
  protected email = this.auth.knownUsers()[0]?.email ?? '';
  protected password = '';
  protected pin = '';
  private readonly returnUrl = computed(
    () => this.route.snapshot.queryParamMap.get('returnUrl') ?? '/',
  );

  constructor() {
    const slug = this.node.info()?.pharmacySlug ?? location.hostname.split('.')[0];
    if (slug && slug !== 'localhost' && slug !== 'app' && !/^\d/.test(slug)) {
      void this.branding.loadPublic(slug);
    }
  }

  protected async passwordLogin(): Promise<void> {
    await this.attempt(() =>
      this.auth.loginWithPassword(
        this.node.isHub() ? null : this.pharmacy.trim(),
        this.email.trim(),
        this.password,
        this.terminal.terminalId,
      ),
    );
  }

  protected async pinLogin(): Promise<void> {
    await this.attempt(() =>
      this.auth.loginWithPin(this.email, this.pin, this.terminal.terminalId),
    );
  }

  private async attempt(login: () => Promise<unknown>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await login();
      await this.branding.load();
      this.sync.start();
      await this.router.navigateByUrl(
        this.auth.mustChangePassword() ? '/account/password' : this.returnUrl(),
      );
    } catch (error) {
      const detail = error as { error?: { detail?: string }; status?: number };
      this.error.set(
        detail.error?.detail ??
          (detail.status === 0
            ? 'Serveur injoignable : vérifiez le réseau de la pharmacie'
            : 'Connexion refusée'),
      );
    } finally {
      this.busy.set(false);
      this.pin = '';
      this.password = '';
    }
  }
}
