import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { APP_ENVIRONMENT } from '../../core/config';
import { Icon } from '../../shared/ui/icon';

interface TenantHealth {
  id: string;
  slug: string;
  legalName: string;
  status: string;
  subscriptionPlan: string;
  subscriptionExpiresAt: string | null;
  sales24h: number;
  terminals: number;
  terminalsOnline: number;
  pendingOfflineSales: number;
  lastSeenAt: string | null;
}

/** Super-Admin supervision of every client pharmacy. */
@Component({
  selector: 'app-platform-dashboard-page',
  imports: [DatePipe, RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Supervision</h1>
          <p class="page-subtitle">
            État des pharmacies clientes, de leurs caisses et de leurs ventes, actualisé toutes les
            30 secondes.
          </p>
        </div>
        <a routerLink="/platform/catalog-import" class="btn btn-secondary">
          <app-icon name="catalog" [size]="16" />
          Référentiel des médicaments
        </a>
      </header>

      <div class="mb-6 grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="building" [size]="16" /></span>
            Pharmacies actives
          </div>
          <div class="kpi-value">{{ active() }}</div>
          <div class="kpi-foot">sur {{ tenants().length }} pharmacie(s) clientes</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="wifi" [size]="16" /></span>
            Caisses en ligne
          </div>
          <div class="kpi-value">
            {{ online() }}
            <span class="text-base font-semibold text-subtle">/ {{ terminals() }}</span>
          </div>
          <div class="kpi-foot">Postes ayant donné signe de vie</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="cart" [size]="16" /></span>
            Ventes 24 h
          </div>
          <div class="kpi-value">{{ sales() }}</div>
          <div class="kpi-foot">Toutes pharmacies confondues</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span
              class="kpi-icon"
              [class.bg-[var(--warning-soft)]]="pending() > 0"
              [class.text-warning]="pending() > 0"
              ><app-icon name="cloudOff" [size]="16"
            /></span>
            Ventes hors-ligne en attente
          </div>
          <div class="kpi-value" [class.text-warning]="pending() > 0">{{ pending() }}</div>
          <div class="kpi-foot">À synchroniser avec le cloud</div>
        </div>
      </div>

      <section class="card overflow-hidden">
        <div class="card-header">
          <h2 class="card-title">Pharmacies clientes</h2>
          <span class="badge">{{ tenants().length }}</span>
        </div>
        <div class="overflow-x-auto">
          <table class="data-table [&_th]:whitespace-nowrap">
            <thead>
              <tr>
                <th>Pharmacie</th>
                <th>Statut</th>
                <th>Abonnement</th>
                <th class="num">Caisses</th>
                <th class="num">Ventes 24 h</th>
                <th class="num">En attente</th>
                <th>Dernier signal</th>
                <th><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (t of tenants(); track t.id) {
                <tr>
                  <td>
                    <div class="flex items-center gap-3">
                      <span
                        class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-soft text-xs font-bold text-brand"
                        >{{ t.slug.charAt(0).toUpperCase() }}</span
                      >
                      <span class="min-w-0">
                        <span class="block font-medium">{{ t.legalName }}</span>
                        <small class="block text-xs text-subtle">{{ t.slug }}</small>
                      </span>
                    </div>
                  </td>
                  <td>
                    <span
                      class="badge"
                      [class.badge-success]="t.status === 'ACTIVE'"
                      [class.badge-danger]="t.status !== 'ACTIVE'"
                      ><span class="dot"></span>{{ t.status }}</span
                    >
                  </td>
                  <td class="whitespace-nowrap">
                    <span class="font-medium">{{ t.subscriptionPlan }}</span>
                    @if (t.subscriptionExpiresAt) {
                      <span class="text-muted">
                        · {{ t.subscriptionExpiresAt | date: 'dd/MM/yyyy' }}</span
                      >
                    }
                  </td>
                  <td class="num">
                    <span
                      class="inline-flex items-center gap-1.5"
                      [class.text-danger]="t.terminals > 0 && t.terminalsOnline === 0"
                    >
                      <span
                        class="dot"
                        [class.text-success]="t.terminalsOnline > 0"
                        [class.text-subtle]="t.terminalsOnline === 0"
                      ></span>
                      {{ t.terminalsOnline }} / {{ t.terminals }}
                    </span>
                  </td>
                  <td class="num">{{ t.sales24h }}</td>
                  <td class="num">
                    @if (t.pendingOfflineSales > 0) {
                      <span class="badge badge-warning">{{ t.pendingOfflineSales }}</span>
                    } @else {
                      <span class="text-subtle">{{ t.pendingOfflineSales }}</span>
                    }
                  </td>
                  <td class="whitespace-nowrap text-muted">
                    {{ t.lastSeenAt ? (t.lastSeenAt | date: 'dd/MM HH:mm') : '—' }}
                  </td>
                  <td class="text-right">
                    <button type="button" class="btn btn-ghost btn-sm" (click)="pairingCode(t)">
                      <app-icon name="key" [size]="14" />
                      Code d'appairage
                    </button>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="8">
                    <div class="empty-state">
                      <app-icon name="building" [size]="22" />
                      <p>Aucune pharmacie cliente pour le moment.</p>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  `,
})
export class PlatformDashboardPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/platform/tenants`;
  protected readonly tenants = signal<TenantHealth[]>([]);
  protected readonly active = computed(
    () => this.tenants().filter((t) => t.status === 'ACTIVE').length,
  );
  protected readonly terminals = computed(() =>
    this.tenants().reduce((sum, t) => sum + t.terminals, 0),
  );
  protected readonly online = computed(() =>
    this.tenants().reduce((sum, t) => sum + t.terminalsOnline, 0),
  );
  protected readonly sales = computed(() => this.tenants().reduce((sum, t) => sum + t.sales24h, 0));
  protected readonly pending = computed(() =>
    this.tenants().reduce((sum, t) => sum + t.pendingOfflineSales, 0),
  );

  constructor() {
    void this.refresh();
    const timer = setInterval(() => void this.refresh(), 30_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected async pairingCode(tenant: TenantHealth): Promise<void> {
    const reply = await firstValueFrom(
      this.http.post<{ code: string; validFor: string }>(
        `${this.api}/${tenant.id}/pairing-codes`,
        {},
      ),
    );
    window.prompt(`Code d'appairage pour ${tenant.legalName} (valable 24 h) :`, reply.code);
  }

  private async refresh(): Promise<void> {
    this.tenants.set(await firstValueFrom(this.http.get<TenantHealth[]>(this.api)));
  }
}
