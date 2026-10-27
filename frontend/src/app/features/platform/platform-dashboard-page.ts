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
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { PharmacySyncHealth } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import {
  badgeClass,
  hubContactBadge,
  pharmacyBadges,
  relativeTime,
} from '../../shared/terminal-sync';
import { Icon } from '../../shared/ui/icon';
import { whileVisible } from '../../shared/visible-interval';

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
            30 secondes. Ouvrez une pharmacie pour voir ses postes et ses comptes.
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
          <div class="kpi-foot">
            @if (staleTotal() > 0 || outOfSyncTotal() > 0) {
              <span [class.text-danger]="staleTotal() > 0">{{ staleTotal() }} hors ligne</span>
              ·
              <span [class.text-warning]="outOfSyncTotal() > 0"
                >{{ outOfSyncTotal() }} à synchroniser</span
              >
            } @else {
              Postes ayant donné signe de vie
            }
          </div>
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
        <div class="hidden overflow-x-auto sm:block">
          <table class="data-table [&_th]:whitespace-nowrap">
            <thead>
              <tr>
                <th>Pharmacie</th>
                <th>Statut</th>
                <th>Abonnement</th>
                <th>Caisses</th>
                <th>Serveur local</th>
                <th class="num">Ventes 24 h</th>
                <th class="num">En attente</th>
                <th><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (t of tenants(); track t.id) {
                <tr
                  class="cursor-pointer"
                  (click)="open(t)"
                  [attr.data-testid]="'tenant-' + t.slug"
                >
                  <td>
                    <div class="flex items-center gap-3">
                      <span
                        class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-soft text-xs font-bold text-brand"
                        >{{ t.slug.charAt(0).toUpperCase() }}</span
                      >
                      <span class="min-w-[11rem]">
                        <a
                          class="block font-medium hover:text-brand focus-visible:text-brand"
                          [routerLink]="['/platform/pharmacies', t.id]"
                          (click)="$event.stopPropagation()"
                          >{{ t.legalName }}</a
                        >
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
                    <span class="block font-medium">{{ t.subscriptionPlan }}</span>
                    @if (t.subscriptionExpiresAt) {
                      <small class="block text-xs text-muted">
                        jusqu'au {{ t.subscriptionExpiresAt | date: 'dd/MM/yyyy' }}</small
                      >
                    }
                  </td>
                  <td>
                    <span
                      class="flex items-center gap-1.5 whitespace-nowrap tabular-nums"
                      [class.text-danger]="t.terminals > 0 && t.terminalsOnline === 0"
                    >
                      <span
                        class="dot"
                        [class.text-success]="t.terminalsOnline > 0"
                        [class.text-subtle]="t.terminalsOnline === 0"
                      ></span>
                      <span title="Caisses en ligne / caisses connues"
                        >{{ t.terminalsOnline }} / {{ t.terminals }}</span
                      >
                      @if (t.lastSeenAt) {
                        <small
                          class="text-xs text-subtle"
                          [attr.title]="
                            'Dernier signal : ' + (t.lastSeenAt | date: 'dd/MM/yyyy HH:mm')
                          "
                          >· {{ ago(t.lastSeenAt) }}</small
                        >
                      }
                    </span>
                    <span class="mt-1 flex flex-wrap gap-1">
                      @for (b of syncBadges(t.id); track b.label) {
                        <span [class]="badgeClass(b.tone)"
                          ><app-icon [name]="b.icon" [size]="12" />{{ b.label }}</span
                        >
                      }
                    </span>
                  </td>
                  <td class="whitespace-nowrap">
                    @if (hubBadgeOf(t.id); as b) {
                      <span [class]="badgeClass(b.tone)" [attr.title]="hubTitle(t.id)"
                        ><app-icon [name]="b.icon" [size]="12" />{{ b.label }}</span
                      >
                    }
                  </td>
                  <td class="num">{{ t.sales24h }}</td>
                  <td class="num">
                    @if (t.pendingOfflineSales > 0) {
                      <span class="badge badge-warning">{{ t.pendingOfflineSales }}</span>
                    } @else {
                      <span class="text-subtle">{{ t.pendingOfflineSales }}</span>
                    }
                  </td>
                  <td class="text-right">
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm btn-icon"
                      (click)="$event.stopPropagation(); pairingCode(t)"
                      [attr.aria-label]="'Code d’appairage pour ' + t.legalName"
                      title="Code d'appairage du serveur local"
                    >
                      <app-icon name="key" [size]="14" />
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
        <ul class="divide-y divide-[var(--border)] sm:hidden" aria-label="Pharmacies clientes">
          @for (t of tenants(); track t.id) {
            <li>
              <a
                class="block space-y-2 px-4 py-3 hover:bg-surface-2"
                [routerLink]="['/platform/pharmacies', t.id]"
              >
                <span class="flex items-start justify-between gap-2">
                  <span class="min-w-0">
                    <span class="block font-medium">{{ t.legalName }}</span>
                    <small class="block text-xs text-subtle"
                      >{{ t.slug }} · {{ t.subscriptionPlan }}</small
                    >
                  </span>
                  <span
                    class="badge"
                    [class.badge-success]="t.status === 'ACTIVE'"
                    [class.badge-danger]="t.status !== 'ACTIVE'"
                    ><span class="dot"></span>{{ t.status }}</span
                  >
                </span>
                <span class="flex flex-wrap items-center gap-1.5 text-xs">
                  <span class="tabular-nums text-muted"
                    >{{ t.terminalsOnline }} / {{ t.terminals }} en ligne</span
                  >
                  @for (b of syncBadges(t.id); track b.label) {
                    <span [class]="badgeClass(b.tone)"
                      ><app-icon [name]="b.icon" [size]="12" />{{ b.label }}</span
                    >
                  }
                  @if (hubBadgeOf(t.id); as b) {
                    <span [class]="badgeClass(b.tone)"
                      ><app-icon [name]="b.icon" [size]="12" />{{ b.label }}</span
                    >
                  }
                </span>
              </a>
            </li>
          } @empty {
            <li class="empty-state">
              <app-icon name="building" [size]="22" />
              <p>Aucune pharmacie cliente pour le moment.</p>
            </li>
          }
        </ul>
      </section>
    </div>
  `,
})
export class PlatformDashboardPage {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/platform/tenants`;
  protected readonly tenants = signal<TenantHealth[]>([]);
  protected readonly health = signal<ReadonlyMap<string, PharmacySyncHealth>>(new Map());
  protected readonly now = signal(Date.now());
  protected readonly badgeClass = badgeClass;
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
  protected readonly staleTotal = computed(() =>
    [...this.health().values()].reduce((sum, h) => sum + h.stale, 0),
  );
  protected readonly outOfSyncTotal = computed(() =>
    [...this.health().values()].reduce((sum, h) => sum + h.outOfSync, 0),
  );

  constructor() {
    void this.refresh();
    whileVisible(() => void this.refresh(), 30_000, inject(DestroyRef));
  }

  protected open(tenant: TenantHealth): void {
    void this.router.navigate(['/platform/pharmacies', tenant.id]);
  }

  protected syncBadges(tenantId: string) {
    return pharmacyBadges(this.health().get(tenantId));
  }

  protected hubBadgeOf(tenantId: string) {
    return hubContactBadge(this.health().get(tenantId), this.now());
  }

  protected ago(at: string): string {
    return relativeTime(at, this.now());
  }

  protected hubTitle(tenantId: string): string | null {
    const health = this.health().get(tenantId);
    return health?.topology === 'HUB'
      ? `Dernier contact du serveur local avec le cloud : ${relativeTime(health.hubLastContactAt, this.now())}`
      : null;
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
    const [tenants, health] = await Promise.all([
      firstValueFrom(this.http.get<TenantHealth[]>(this.api)),
      firstValueFrom(this.http.get<PharmacySyncHealth[]>(`${this.api}/sync-health`)).catch(
        () => null,
      ),
    ]);
    this.tenants.set(tenants);
    if (health) {
      this.health.set(new Map(health.map((h) => [h.tenantId, h])));
    }
    this.now.set(Date.now());
  }
}
