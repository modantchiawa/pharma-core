import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { NodeInfoService } from '../../core/api/node-info.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { SaleOutboxService } from '../../core/offline/sale-outbox.service';

interface SyncStatus {
  paired: boolean;
  running: boolean;
  cloudReachable: boolean;
  lastPushAt: string | null;
  lastPullAt: string | null;
  pendingChanges: number;
  lastError: string | null;
  bootstrapping: boolean;
}

/** Health of the local server: cloud link, pending changes, terminals' offline journals. */
@Component({
  selector: 'app-sync-status-page',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-2xl space-y-4 p-4">
      <section class="rounded-lg border bg-white p-4">
        <h2 class="mb-2 text-lg font-semibold">Serveur local</h2>
        <dl class="grid grid-cols-2 gap-2 text-sm">
          <dt>Identifiant</dt>
          <dd>
            <code>{{ node.info()?.nodeId }}</code>
          </dd>
          <dt>Pharmacie</dt>
          <dd>{{ node.info()?.pharmacySlug }}</dd>
          <dt>Version</dt>
          <dd>{{ node.info()?.version }}</dd>
          <dt>Cloud</dt>
          <dd>{{ node.info()?.cloudUrl }}</dd>
        </dl>
      </section>
      @if (status(); as s) {
        <section class="rounded-lg border bg-white p-4">
          <h2 class="mb-2 text-lg font-semibold">Synchronisation avec le cloud</h2>
          <p class="mb-2">
            <span
              class="rounded-full px-2 py-0.5 text-white"
              [class.bg-green-600]="s.cloudReachable"
              [class.bg-amber-500]="!s.cloudReachable"
            >
              {{
                s.cloudReachable
                  ? 'Cloud joignable'
                  : 'Cloud injoignable — les données restent sur le serveur local'
              }}
            </span>
            @if (s.bootstrapping) {
              <span class="ml-2 text-sm">chargement initial en cours…</span>
            }
          </p>
          <dl class="grid grid-cols-2 gap-2 text-sm">
            <dt>Changements en attente d'envoi</dt>
            <dd [class.font-bold]="s.pendingChanges > 0">{{ s.pendingChanges }}</dd>
            <dt>Dernier envoi</dt>
            <dd>{{ s.lastPushAt ? (s.lastPushAt | date: 'dd/MM HH:mm:ss') : 'jamais' }}</dd>
            <dt>Dernière réception</dt>
            <dd>{{ s.lastPullAt ? (s.lastPullAt | date: 'dd/MM HH:mm:ss') : 'jamais' }}</dd>
            @if (s.lastError) {
              <dt>Dernière erreur</dt>
              <dd class="text-red-700">{{ s.lastError }}</dd>
            }
          </dl>
          <button
            type="button"
            class="mt-3 rounded bg-brand px-3 py-1 text-on-brand"
            (click)="syncNow()"
            [disabled]="s.running"
          >
            Synchroniser maintenant
          </button>
        </section>
      }
      <section class="rounded-lg border bg-white p-4">
        <h2 class="mb-2 text-lg font-semibold">Ce poste</h2>
        <p class="text-sm">
          Ventes en attente d'envoi au serveur : <strong>{{ outbox.pendingCount() }}</strong
          >, rejetées : <strong>{{ outbox.rejectedCount() }}</strong
          >, dernière synchro :
          {{ outbox.lastSyncAt() ? (outbox.lastSyncAt() | date: 'dd/MM HH:mm') : '—' }}
        </p>
      </section>
    </div>
  `,
})
export class SyncStatusPage {
  protected readonly node = inject(NodeInfoService);
  protected readonly outbox = inject(SaleOutboxService);
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  protected readonly status = signal<SyncStatus | null>(null);

  constructor() {
    void this.refresh();
    const timer = setInterval(() => void this.refresh(), 10_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected async syncNow(): Promise<void> {
    this.status.set(
      await firstValueFrom(this.http.post<SyncStatus>(`${this.env.apiUrl}/v1/hub/sync/run`, {})),
    );
  }

  private async refresh(): Promise<void> {
    try {
      this.status.set(
        await firstValueFrom(this.http.get<SyncStatus>(`${this.env.apiUrl}/v1/hub/sync`)),
      );
    } catch {
      // not a hub or no permission
    }
  }
}
