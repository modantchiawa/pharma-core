import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { PermissionService } from './core/auth/permission.service';
import { BrandingService } from './core/branding/branding.service';
import { ConnectivityService } from './core/offline/connectivity.service';
import { SaleOutboxService } from './core/offline/sale-outbox.service';
import { Density, DisplayPreferencesService } from './core/ui/display-preferences.service';

interface NavItem {
  path: string;
  label: string;
  permissions: string[];
}

const PHARMACY_NAV: NavItem[] = [
  { path: '/pos', label: 'Vente', permissions: ['SALE_CREATE'] },
  { path: '/cash', label: 'Caisse', permissions: ['CASH_SESSION_MANAGE', 'REPORT_VIEW'] },
  { path: '/customers', label: 'Clients', permissions: ['CUSTOMER_MANAGE'] },
  { path: '/inventory', label: 'Stocks', permissions: ['STOCK_VIEW'] },
  { path: '/procurement', label: 'Commandes', permissions: ['PROCUREMENT_VIEW'] },
  { path: '/accounting', label: 'Compta', permissions: ['ACCOUNTING_VIEW'] },
  {
    path: '/network',
    label: 'Réseau',
    permissions: ['NETWORK_STOCK_QUERY', 'NETWORK_TRANSFER_REQUEST', 'NETWORK_TRANSFER_RESPOND'],
  },
  { path: '/guards', label: 'Gardes', permissions: ['GUARD_VIEW'] },
  { path: '/chat', label: 'Messagerie', permissions: ['NETWORK_CHAT'] },
  { path: '/admin/branding', label: 'Identité', permissions: ['CONFIG_BRANDING_MANAGE'] },
  { path: '/admin/roles', label: 'Droits', permissions: ['ROLE_MANAGE'] },
  { path: '/admin/hub', label: 'Serveur', permissions: ['HUB_ADMIN'] },
];

/** Branded application frame: navigation filtered by permissions and live sync status. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex h-screen flex-col bg-slate-50 text-slate-900">
      <header class="flex items-center gap-4 bg-brand px-4 py-2 text-on-brand print:hidden">
        @if (branding.logoUrl(); as logo) {
          <img [src]="logo" alt="" class="h-9 rounded bg-white p-0.5" />
        }
        <span class="text-lg font-semibold">{{ branding.branding().displayName }}</span>
        <nav class="ml-6 flex gap-1" aria-label="Navigation principale">
          @for (item of nav(); track item.path) {
            <a
              [routerLink]="item.path"
              routerLinkActive="bg-white/20"
              class="rounded px-3 py-1 hover:bg-white/10"
              >{{ item.label }}</a
            >
          }
        </nav>
        <div class="ml-auto flex items-center gap-3 text-sm">
          @if (!auth.isPlatformOperator()) {
            <span
              class="rounded-full px-2 py-0.5"
              [class.bg-green-600]="connectivity.online()"
              [class.bg-amber-500]="!connectivity.online()"
              role="status"
              >{{ connectivity.online() ? 'En ligne' : 'Hors-ligne' }}</span
            >
            @if (outbox.pendingCount() > 0) {
              <span title="Ventes en attente de synchronisation"
                >⇅ {{ outbox.pendingCount() }}</span
              >
            }
            @if (outbox.rejectedCount() > 0) {
              <span class="rounded bg-red-600 px-2 text-white"
                >{{ outbox.rejectedCount() }} rejet(s)</span
              >
            }
          }
          <details class="relative">
            <summary class="cursor-pointer list-none rounded px-2 py-0.5 hover:bg-white/10">
              Affichage ▾
            </summary>
            <div
              class="absolute right-0 z-20 mt-1 w-56 space-y-2 rounded border bg-white p-3 text-slate-900 shadow-lg"
            >
              <label class="block text-xs"
                >Densité
                <select
                  class="mt-1 w-full rounded border px-1"
                  [value]="display.preferences().density"
                  (change)="setDensity($event)"
                >
                  <option value="COMPACT">Compacte</option>
                  <option value="COMFORTABLE">Confortable</option>
                  <option value="LARGE">Grande</option>
                </select>
              </label>
              <label class="block text-xs"
                >Taille du texte
                <select
                  class="mt-1 w-full rounded border px-1"
                  [value]="display.preferences().fontScale"
                  (change)="setScale($event)"
                >
                  <option value="1">Normale</option>
                  <option value="1.15">Grande</option>
                  <option value="1.3">Très grande</option>
                </select>
              </label>
              <label class="flex items-center gap-2 text-xs"
                ><input
                  type="checkbox"
                  [checked]="display.preferences().highContrast"
                  (change)="toggle('highContrast', $event)"
                />
                Contraste élevé (soleil)</label
              >
              <label class="flex items-center gap-2 text-xs"
                ><input
                  type="checkbox"
                  [checked]="display.preferences().darkMode"
                  (change)="toggle('darkMode', $event)"
                />
                Mode sombre (garde)</label
              >
            </div>
          </details>
          <a routerLink="/account/password" class="hover:underline">{{ userName() }}</a>
          <button type="button" class="underline" (click)="logout()">Déconnexion</button>
        </div>
      </header>
      @if (auth.mustChangePassword()) {
        <p class="bg-amber-100 px-4 py-1 text-center text-sm text-amber-900 print:hidden">
          Votre mot de passe est temporaire :
          <a routerLink="/account/password" class="underline">changez-le maintenant</a>.
        </p>
      }
      <main class="min-h-0 flex-1 overflow-auto"><router-outlet /></main>
    </div>
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly branding = inject(BrandingService);
  protected readonly connectivity = inject(ConnectivityService);
  protected readonly outbox = inject(SaleOutboxService);
  private readonly permissions = inject(PermissionService);
  protected readonly display = inject(DisplayPreferencesService);
  private readonly router = inject(Router);

  protected setDensity(event: Event): void {
    this.display.update({ density: (event.target as HTMLSelectElement).value as Density });
  }

  protected setScale(event: Event): void {
    this.display.update({
      fontScale: Number((event.target as HTMLSelectElement).value) as 1 | 1.15 | 1.3,
    });
  }

  protected toggle(key: 'highContrast' | 'darkMode', event: Event): void {
    this.display.update({ [key]: (event.target as HTMLInputElement).checked });
  }

  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/login');
  }

  protected readonly nav = computed(() =>
    this.auth.isPlatformOperator()
      ? [{ path: '/platform', label: 'Supervision', permissions: [] }]
      : PHARMACY_NAV.filter((item) => this.permissions.hasAny(...item.permissions)),
  );
  protected readonly userName = computed(
    () =>
      this.permissions.profile()?.displayName ??
      (this.auth.user()?.profile?.name as string | undefined) ??
      '',
  );
}
