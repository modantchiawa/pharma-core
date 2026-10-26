import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { NodeInfoService } from '../../core/api/node-info.service';
import { AuthService } from '../../core/auth/auth.service';
import { BrandingService } from '../../core/branding/branding.service';
import { SyncOrchestratorService } from '../../core/offline/sync-orchestrator.service';
import { TerminalService } from '../../core/offline/terminal.service';
import { APP_ENVIRONMENT } from '../../core/config';

interface DemoAccount {
  role: string;
  email: string;
  pharmacy: string;
}

/** Accounts created by the backend's demonstration seed (pharmacore.demo.enabled). */
const DEMO_ACCOUNTS: DemoAccount[] = [
  { role: 'Titulaire', email: 'titulaire@sahel.demo', pharmacy: 'sahel' },
  { role: 'Caissier', email: 'caisse@sahel.demo', pharmacy: 'sahel' },
  { role: 'Titulaire', email: 'titulaire@lacouroussou.demo', pharmacy: 'lacouroussou' },
  { role: 'Caissier', email: 'caisse@lacouroussou.demo', pharmacy: 'lacouroussou' },
];

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
        @if (demo) {
          <details class="rounded border border-dashed border-amber-400 bg-amber-50 p-3 text-xs">
            <summary class="cursor-pointer font-semibold text-amber-900">
              Environnement de démonstration · comptes de test
            </summary>
            <table class="mt-2 w-full text-left">
              <tbody>
                @for (account of demoAccounts; track account.email) {
                  <tr class="border-t border-amber-200">
                    <td class="py-1 pr-2">{{ account.role }}</td>
                    <td class="py-1 pr-2">
                      <button type="button" class="underline" (click)="useDemoAccount(account)">
                        {{ account.email }}
                      </button>
                    </td>
                    <td class="py-1">{{ account.pharmacy }}</td>
                  </tr>
                }
              </tbody>
            </table>
            <p class="mt-2">
              Mot de passe <code>Demo2026!</code> · PIN <code>1234</code> · poste de démonstration
              <code>DEMO-1</code>. Les données sont rechargées à chaque redéploiement.
            </p>
            <p class="mt-1 text-amber-800">
              API : <code>{{ apiUrl }}</code>
            </p>
            <form class="mt-3 flex gap-2" (ngSubmit)="platformDemoLogin()">
              <input
                type="password"
                class="flex-1 rounded border px-2 py-1"
                placeholder="Mot de passe console éditeur"
                [(ngModel)]="platformPassword"
                name="platformPassword"
                autocomplete="off"
              />
              <button
                type="submit"
                class="rounded bg-brand px-3 py-1 text-on-brand"
                [disabled]="busy()"
              >
                Console éditeur
              </button>
            </form>
          </details>
        } @else {
          <button
            type="button"
            class="w-full text-xs text-slate-400 underline"
            (click)="auth.loginPlatform()"
          >
            Console éditeur
          </button>
        }
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
  protected readonly demo = inject(APP_ENVIRONMENT).demo;
  protected readonly apiUrl = inject(APP_ENVIRONMENT).apiUrl;
  protected readonly demoAccounts = DEMO_ACCOUNTS;
  protected platformPassword = '';
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

  protected useDemoAccount(account: DemoAccount): void {
    this.mode.set('password');
    this.pharmacy = account.pharmacy;
    this.email = account.email;
    this.password = 'Demo2026!';
  }

  protected async platformDemoLogin(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.auth.loginPlatformWithPassword(this.platformPassword);
      await this.router.navigateByUrl('/platform');
    } catch (error) {
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Console éditeur refusée',
      );
    } finally {
      this.busy.set(false);
      this.platformPassword = '';
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
      this.error.set(loginErrorMessage(error, this.apiUrl));
    } finally {
      this.busy.set(false);
      this.pin = '';
      this.password = '';
    }
  }
}

/**
 * A PharmaCore error always carries a readable detail; anything else means the request reached
 * another program (wrong address or port, proxy) or failed on the way, and says so with the status.
 */
export function loginErrorMessage(error: unknown, apiUrl: string): string {
  const response = error as { error?: { detail?: string }; status?: number; url?: string | null };
  if (response.error?.detail) {
    return response.error.detail;
  }
  const status = response.status ?? 0;
  if (status === 0) {
    return `Serveur injoignable (${apiUrl}) : vérifiez que le backend tourne et le réseau de la pharmacie`;
  }
  if (status === 404 || status === 405) {
    return `Aucun serveur PharmaCore à ${apiUrl} (HTTP ${status}) : vérifiez l'adresse et le port de l'API`;
  }
  if (status === 401 || status === 403) {
    return `Accès refusé par ${apiUrl} (HTTP ${status}) : origine non autorisée (ALLOWED_ORIGINS) ou autre serveur sur ce port`;
  }
  if (status >= 500) {
    return `Erreur du serveur (HTTP ${status}) : consultez le journal du backend`;
  }
  return `Connexion refusée (HTTP ${status})`;
}
