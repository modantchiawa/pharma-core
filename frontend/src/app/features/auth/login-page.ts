import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { NodeInfoService } from '../../core/api/node-info.service';
import { AuthService } from '../../core/auth/auth.service';
import { BrandingService } from '../../core/branding/branding.service';
import { SyncOrchestratorService } from '../../core/offline/sync-orchestrator.service';
import { TerminalService } from '../../core/offline/terminal.service';
import { TerminalNamesService } from '../../core/offline/terminal-names.service';
import { AppUpdateService } from '../../core/update/app-update.service';
import { versionLabel } from '../../core/update/build-info';
import { APP_ENVIRONMENT } from '../../core/config';
import { closedSessionNotice } from '../../core/session/idle';
import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons';

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

const FEATURES: { icon: IconName; title: string; text: string }[] = [
  {
    icon: 'cloudOff',
    title: 'Hors ligne d’abord',
    text: 'Le serveur de la pharmacie prend le relais, tout se synchronise au retour du réseau.',
  },
  {
    icon: 'mobileMoney',
    title: 'Mobile money et crédit client',
    text: 'Espèces, Airtel Money, Zamani Cash, tiers payant : un seul encaissement.',
  },
  {
    icon: 'accounting',
    title: 'Comptabilité SYSCOHADA',
    text: 'Écritures générées à chaque vente, balance et bilan sans ressaisie.',
  },
];

/**
 * Sign-in without Internet: credentials are checked by the local hub (or the cloud for
 * pharmacies without hub). Once a password login has bound the terminal, colleagues switch in
 * with their PIN in two taps.
 */
@Component({
  selector: 'app-login-page',
  imports: [FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="grid min-h-screen bg-surface lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
      <aside class="hero relative hidden overflow-hidden p-12 text-on-brand lg:flex lg:flex-col">
        <div class="flex items-center gap-3">
          @if (branding.logoUrl(); as logo) {
            <img [src]="logo" alt="" class="h-11 w-11 rounded-xl bg-white/95 object-contain p-1" />
          } @else {
            <span
              class="flex h-11 w-11 items-center justify-center rounded-xl bg-white/15 ring-1 ring-white/25"
            >
              <app-icon name="pill" [size]="22" />
            </span>
          }
          <span class="text-lg font-semibold">{{ branding.branding().displayName }}</span>
        </div>

        <div class="relative mt-auto max-w-lg">
          <h2 class="text-4xl leading-tight font-bold tracking-tight">
            Toute l'officine, au comptoir comme au bureau.
          </h2>
          <p class="mt-4 text-base text-white/75">
            Vente, stock, caisse et comptabilité, même quand Internet tombe.
          </p>
          <ul class="mt-10 space-y-4">
            @for (feature of features; track feature.title) {
              <li class="flex items-start gap-4">
                <span
                  class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-white/12 ring-1 ring-white/20"
                >
                  <app-icon [name]="feature.icon" [size]="18" />
                </span>
                <div>
                  <p class="font-semibold">{{ feature.title }}</p>
                  <p class="text-sm text-white/70">{{ feature.text }}</p>
                </div>
              </li>
            }
          </ul>
        </div>

        <p class="relative mt-12 text-xs text-white/60">
          Poste {{ terminal.terminalId }} · {{ node.isHub() ? 'serveur local' : 'cloud' }}
        </p>
      </aside>

      <section class="flex items-center justify-center px-4 py-10 sm:px-8">
        <div class="animate-in w-full max-w-[25rem]">
          <div class="mb-8 flex items-center gap-3 lg:hidden">
            @if (branding.logoUrl(); as logo) {
              <img [src]="logo" alt="" class="h-11 w-11 rounded-xl object-contain" />
            } @else {
              <span
                class="flex h-11 w-11 items-center justify-center rounded-xl bg-brand text-on-brand"
              >
                <app-icon name="pill" [size]="22" />
              </span>
            }
            <div>
              <p class="font-semibold">{{ branding.branding().displayName }}</p>
              <p class="text-xs text-muted">
                Poste {{ terminal.terminalId }} · {{ node.isHub() ? 'serveur local' : 'cloud' }}
              </p>
            </div>
          </div>

          <h1 class="text-2xl font-bold tracking-tight">
            {{ mode() === 'pin' && knownUsers().length > 0 ? 'Changer de vendeur' : 'Connexion' }}
          </h1>
          <p class="mt-1.5 text-sm text-muted">
            {{
              mode() === 'pin' && knownUsers().length > 0
                ? 'Choisissez votre nom et saisissez votre code PIN.'
                : 'Identifiez-vous pour ouvrir votre session de travail.'
            }}
          </p>

          @if (closedNotice; as notice) {
            <p class="alert alert-info mt-6" role="status" data-testid="session-closed">
              <app-icon name="timer" [size]="18" />
              <span>{{ notice }}</span>
            </p>
          }

          @if (mode() === 'pin' && knownUsers().length > 0) {
            <form class="mt-8 space-y-5" (ngSubmit)="pinLogin()">
              <div>
                <label class="label" for="login-user">Utilisateur</label>
                <select id="login-user" class="select" [(ngModel)]="email" name="email">
                  @for (u of knownUsers(); track u.email) {
                    <option [value]="u.email">{{ u.name }}</option>
                  }
                </select>
              </div>
              <div>
                <label class="label" for="login-pin">Code PIN</label>
                <input
                  id="login-pin"
                  type="password"
                  inputmode="numeric"
                  autocomplete="off"
                  maxlength="6"
                  class="input h-14 text-center text-2xl tracking-[0.6em]"
                  [(ngModel)]="pin"
                  name="pin"
                  autofocus
                />
              </div>
              <button type="submit" class="btn btn-primary btn-lg w-full" [disabled]="busy()">
                <app-icon name="lock" [size]="18" />
                Entrer
              </button>
              <button type="button" class="btn btn-ghost w-full" (click)="mode.set('password')">
                Se connecter avec un mot de passe
              </button>
            </form>
          } @else {
            <form class="mt-8 space-y-5" (ngSubmit)="passwordLogin()">
              @if (!node.isHub()) {
                <div>
                  <label class="label" for="login-pharmacy">Identifiant de la pharmacie</label>
                  <label class="input-with-icon">
                    <app-icon name="store" [size]="16" />
                    <input
                      id="login-pharmacy"
                      class="input"
                      [(ngModel)]="pharmacy"
                      name="pharmacy"
                      autocomplete="organization"
                      placeholder="ex. sahel"
                    />
                  </label>
                </div>
              }
              <div>
                <label class="label" for="login-email">E-mail</label>
                <label class="input-with-icon">
                  <app-icon name="user" [size]="16" />
                  <input
                    id="login-email"
                    type="email"
                    class="input"
                    [(ngModel)]="email"
                    name="email"
                    autocomplete="username"
                    placeholder="prenom@pharmacie.ne"
                    required
                  />
                </label>
              </div>
              <div>
                <label class="label" for="login-password">Mot de passe</label>
                <div class="relative">
                  <label class="input-with-icon">
                    <app-icon name="lock" [size]="16" />
                    <input
                      id="login-password"
                      [type]="showPassword() ? 'text' : 'password'"
                      class="input pr-11"
                      [(ngModel)]="password"
                      name="password"
                      autocomplete="current-password"
                      required
                    />
                  </label>
                  <button
                    type="button"
                    class="btn btn-ghost btn-icon btn-sm absolute top-1/2 right-1.5 -translate-y-1/2"
                    [attr.aria-label]="
                      showPassword() ? 'Masquer le mot de passe' : 'Afficher le mot de passe'
                    "
                    (click)="showPassword.set(!showPassword())"
                  >
                    <app-icon [name]="showPassword() ? 'eyeOff' : 'eye'" [size]="16" />
                  </button>
                </div>
              </div>
              <button type="submit" class="btn btn-primary btn-lg w-full" [disabled]="busy()">
                Connexion
                <app-icon name="arrowRight" [size]="18" />
              </button>
              @if (auth.terminalBound() && knownUsers().length > 0) {
                <button type="button" class="btn btn-ghost w-full" (click)="mode.set('pin')">
                  <app-icon name="zap" [size]="16" />
                  Bascule rapide par code PIN
                </button>
              }
            </form>
          }

          @if (error(); as text) {
            <div class="alert alert-danger mt-5" role="alert">
              <app-icon name="error" [size]="18" />
              <span>{{ text }}</span>
            </div>
          }

          @if (demo) {
            <details
              class="demo mt-8 rounded-[var(--radius)] border border-dashed bg-[var(--warning-soft)]"
            >
              <summary
                class="flex cursor-pointer list-none items-center gap-2 px-4 py-3 text-sm font-semibold text-warning"
              >
                <app-icon name="sparkles" [size]="16" />
                Comptes de démonstration
                <app-icon name="chevronDown" [size]="16" class="chevron ml-auto" />
              </summary>
              <div class="space-y-3 px-4 pb-4 text-xs">
                <div class="grid gap-2 sm:grid-cols-2">
                  @for (account of demoAccounts; track account.email) {
                    <button
                      type="button"
                      class="rounded-[var(--radius-sm)] border bg-surface px-3 py-2 text-left transition hover:border-[var(--brand-primary)] hover:shadow-sm"
                      (click)="useDemoAccount(account)"
                    >
                      <span class="block font-semibold text-ink"
                        >{{ account.role }} · {{ account.pharmacy }}</span
                      >
                      <span class="block truncate text-muted">{{ account.email }}</span>
                    </button>
                  }
                </div>
                <p class="text-muted">
                  Mot de passe <span class="kbd">Demo2026!</span> · PIN
                  <span class="kbd">1234</span> · poste <span class="kbd">DEMO-1</span>. Les données
                  sont rechargées à chaque redéploiement.
                </p>
                <p class="truncate text-subtle">
                  API : <code>{{ apiUrl }}</code>
                </p>
                <form class="flex gap-2 pt-1" (ngSubmit)="platformDemoLogin()">
                  <input
                    type="password"
                    class="input flex-1"
                    placeholder="Mot de passe console éditeur"
                    [(ngModel)]="platformPassword"
                    name="platformPassword"
                    autocomplete="off"
                  />
                  <button type="submit" class="btn btn-secondary" [disabled]="busy()">
                    <app-icon name="building" [size]="16" />
                    Console éditeur
                  </button>
                </form>
              </div>
            </details>
          } @else {
            <div class="mt-10 flex items-center gap-3 text-xs text-subtle">
              <span class="divider flex-1"></span>
              <button type="button" class="hover:text-ink" (click)="auth.loginPlatform()">
                Console éditeur
              </button>
              <span class="divider flex-1"></span>
            </div>
          }
          <p class="mt-6 text-center text-xs text-subtle" data-testid="login-version">
            {{ stationName() }} · PharmaCore {{ version }} ·
            <button type="button" class="underline hover:text-ink" (click)="forceUpdate()">
              Forcer la mise à jour
            </button>
          </p>
        </div>
      </section>
    </main>
  `,
  styles: `
    .hero {
      background:
        radial-gradient(50rem 28rem at 105% -5%, rgb(255 255 255 / 0.16), transparent 60%),
        radial-gradient(
          40rem 30rem at -20% 120%,
          color-mix(in oklab, var(--brand-secondary) 55%, transparent),
          transparent 60%
        ),
        linear-gradient(
          150deg,
          var(--brand-primary),
          color-mix(in oklab, var(--brand-primary) 50%, black)
        );
    }
    .hero h2 {
      color: inherit;
    }
    .hero::after {
      content: '';
      position: absolute;
      inset: 0;
      background-image: radial-gradient(rgb(255 255 255 / 0.08) 1px, transparent 1px);
      background-size: 22px 22px;
      mask-image: linear-gradient(to bottom, black, transparent 70%);
      pointer-events: none;
    }
    .demo {
      border-color: color-mix(in oklab, var(--warning) 45%, transparent);
    }
    .demo summary::-webkit-details-marker {
      display: none;
    }
    .demo[open] .chevron {
      transform: rotate(180deg);
    }
  `,
})
export class LoginPage {
  protected forceUpdate(): void {
    if (confirm("Recharger PharmaCore depuis le serveur en vidant le cache de l'application ?")) {
      void this.updates.forceReload();
    }
  }

  protected readonly branding = inject(BrandingService);
  protected readonly auth = inject(AuthService);
  protected readonly node = inject(NodeInfoService);
  protected readonly terminal = inject(TerminalService);
  private readonly updates = inject(AppUpdateService);
  private readonly stations = inject(TerminalNamesService);
  protected readonly version = versionLabel();
  /** The station's name as last known here (names are kept on the station), else its id. */
  protected readonly stationName = computed(
    () => this.stations.self().name ?? `Poste ${this.terminal.terminalId}`,
  );
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
  protected readonly features = FEATURES;
  protected readonly showPassword = signal(false);
  protected platformPassword = '';
  protected pharmacy = this.auth.lastPharmacy() ?? '';
  protected email = this.auth.knownUsers()[0]?.email ?? '';
  protected password = '';
  protected pin = '';
  private readonly returnUrl = computed(
    () => this.route.snapshot.queryParamMap.get('returnUrl') ?? '/',
  );
  /** Why the previous session ended on its own (inactivity, wrong codes on the lock screen). */
  protected readonly closedNotice = closedSessionNotice(
    this.route.snapshot.queryParamMap.get('session'),
    Number(this.route.snapshot.queryParamMap.get('minutes')) || null,
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
