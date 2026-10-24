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
import { firstValueFrom } from 'rxjs';
import { APP_ENVIRONMENT } from '../../core/config';

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
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <div class="grid gap-3 sm:grid-cols-4">
        <div class="rounded-lg border bg-white p-4">
          <p class="text-sm text-slate-500">Pharmacies actives</p>
          <p class="text-2xl font-bold">{{ active() }}</p>
        </div>
        <div class="rounded-lg border bg-white p-4">
          <p class="text-sm text-slate-500">Caisses en ligne</p>
          <p class="text-2xl font-bold">{{ online() }} / {{ terminals() }}</p>
        </div>
        <div class="rounded-lg border bg-white p-4">
          <p class="text-sm text-slate-500">Ventes 24 h</p>
          <p class="text-2xl font-bold">{{ sales() }}</p>
        </div>
        <div class="rounded-lg border bg-white p-4">
          <p class="text-sm text-slate-500">Ventes hors-ligne en attente</p>
          <p class="text-2xl font-bold">{{ pending() }}</p>
        </div>
      </div>
      <table class="w-full rounded border bg-white text-sm">
        <thead class="bg-slate-100 text-left">
          <tr>
            <th class="p-2">Pharmacie</th>
            <th class="p-2">Statut</th>
            <th class="p-2">Abonnement</th>
            <th class="p-2">Caisses</th>
            <th class="p-2">Ventes 24 h</th>
            <th class="p-2">En attente</th>
            <th class="p-2">Dernier signal</th>
          </tr>
        </thead>
        <tbody>
          @for (t of tenants(); track t.id) {
            <tr class="border-t">
              <td class="p-2">
                {{ t.legalName }} <small class="text-slate-500">{{ t.slug }}</small>
              </td>
              <td class="p-2">
                <span
                  class="rounded px-2 py-0.5"
                  [class.bg-green-100]="t.status === 'ACTIVE'"
                  [class.bg-red-100]="t.status !== 'ACTIVE'"
                  >{{ t.status }}</span
                >
              </td>
              <td class="p-2">
                {{ t.subscriptionPlan }}
                @if (t.subscriptionExpiresAt) {
                  · {{ t.subscriptionExpiresAt | date: 'dd/MM/yyyy' }}
                }
              </td>
              <td class="p-2" [class.text-red-700]="t.terminals > 0 && t.terminalsOnline === 0">
                {{ t.terminalsOnline }} / {{ t.terminals }}
              </td>
              <td class="p-2">{{ t.sales24h }}</td>
              <td class="p-2" [class.font-bold]="t.pendingOfflineSales > 0">
                {{ t.pendingOfflineSales }}
              </td>
              <td class="p-2">{{ t.lastSeenAt | date: 'dd/MM HH:mm' }}</td>
              <td class="p-2">
                <button type="button" class="underline" (click)="pairingCode(t)">
                  Code d'appairage
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
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
