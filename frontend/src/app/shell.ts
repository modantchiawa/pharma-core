import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth/auth.service';
import { PermissionService } from './core/auth/permission.service';
import { BrandingService } from './core/branding/branding.service';
import { ConnectivityService } from './core/offline/connectivity.service';
import { SaleOutboxService } from './core/offline/sale-outbox.service';

interface NavItem {
  path: string;
  label: string;
  permissions: string[];
}

const PHARMACY_NAV: NavItem[] = [
  { path: '/pos', label: 'Caisse', permissions: ['SALE_CREATE'] },
  { path: '/inventory', label: 'Stocks', permissions: ['STOCK_VIEW'] },
  {
    path: '/network',
    label: 'Réseau',
    permissions: ['NETWORK_STOCK_QUERY', 'NETWORK_TRANSFER_REQUEST', 'NETWORK_TRANSFER_RESPOND'],
  },
  { path: '/guards', label: 'Gardes', permissions: ['GUARD_VIEW'] },
  { path: '/chat', label: 'Messagerie', permissions: ['NETWORK_CHAT'] },
  { path: '/admin/branding', label: 'Identité', permissions: ['CONFIG_BRANDING_MANAGE'] },
  { path: '/admin/roles', label: 'Droits', permissions: ['ROLE_MANAGE'] },
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
          <span>{{ userName() }}</span>
          <button type="button" class="underline" (click)="auth.logout()">Déconnexion</button>
        </div>
      </header>
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
