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
import { Observable, firstValueFrom } from 'rxjs';
import { TerminalBoard, TerminalView } from '../../core/api/models';
import { NodeInfoService } from '../../core/api/node-info.service';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { SaleOutboxService } from '../../core/offline/sale-outbox.service';
import { TerminalService } from '../../core/offline/terminal.service';
import { ToastService } from '../../core/ui/toast.service';
import { plural } from '../../shared/terminal-sync';
import { Icon } from '../../shared/ui/icon';
import { TerminalBoardView } from '../../shared/ui/terminal-board';
import { whileVisible } from '../../shared/visible-interval';

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
  imports: [DatePipe, Icon, TerminalBoardView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Serveur local</h1>
          <p class="page-subtitle">
            Liaison avec le cloud, changements en attente, postes de caisse de l'officine et ventes
            hors ligne de ce poste.
          </p>
        </div>
        @if (status(); as s) {
          <button type="button" class="btn btn-primary" (click)="syncNow()" [disabled]="s.running">
            <app-icon name="refresh" [size]="16" [class.animate-spin]="s.running" />
            Synchroniser maintenant
          </button>
        }
      </header>

      <div class="space-y-6">
        @if (status(); as s) {
          <section class="space-y-4" aria-label="Synchronisation avec le cloud">
            <div
              class="alert items-center"
              [class.alert-success]="s.cloudReachable"
              [class.alert-warning]="!s.cloudReachable"
              role="status"
            >
              <app-icon [name]="s.cloudReachable ? 'wifi' : 'cloudOff'" [size]="18" />
              <span class="flex-1 font-medium">
                {{
                  s.cloudReachable
                    ? 'Cloud joignable'
                    : 'Cloud injoignable — les données restent sur le serveur local'
                }}
              </span>
              @if (s.bootstrapping) {
                <span class="badge badge-info"
                  ><span class="dot animate-pulse"></span>chargement initial en cours…</span
                >
              }
            </div>

            <div class="grid gap-4 sm:grid-cols-3">
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="activity" [size]="16" /></span>
                  Changements en attente d'envoi
                </div>
                <div class="kpi-value" [class.text-warning]="s.pendingChanges > 0">
                  {{ s.pendingChanges }}
                </div>
                <div class="kpi-foot">
                  {{ s.pendingChanges > 0 ? 'Envoi au prochain cycle' : 'Tout est envoyé' }}
                </div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="trendUpRight" [size]="16" /></span>
                  Dernier envoi
                </div>
                <div class="kpi-value text-xl">
                  {{ s.lastPushAt ? (s.lastPushAt | date: 'dd/MM HH:mm:ss') : 'jamais' }}
                </div>
                <div class="kpi-foot">Du serveur local vers le cloud</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="trendDownRight" [size]="16" /></span>
                  Dernière réception
                </div>
                <div class="kpi-value text-xl">
                  {{ s.lastPullAt ? (s.lastPullAt | date: 'dd/MM HH:mm:ss') : 'jamais' }}
                </div>
                <div class="kpi-foot">Du cloud vers le serveur local</div>
              </div>
            </div>

            @if (s.lastError) {
              <div class="alert alert-danger" role="alert">
                <app-icon name="error" [size]="18" />
                <div class="min-w-0">
                  <p class="font-semibold">Dernière erreur</p>
                  <p class="break-words">{{ s.lastError }}</p>
                </div>
              </div>
            }
          </section>
        } @else {
          <section class="card">
            <div class="empty-state">
              <app-icon name="cloudOff" [size]="22" />
              <p class="font-medium text-ink">Synchronisation non disponible</p>
              <p>Ce serveur n'est pas un serveur d'officine, ou son état n'est pas consultable.</p>
            </div>
          </section>
        }

        <div class="grid gap-6 lg:grid-cols-2">
          <section class="card">
            <div class="card-header">
              <h2 class="card-title flex items-center gap-2">
                <app-icon name="server" [size]="16" class="text-muted" />
                Serveur local
              </h2>
            </div>
            <dl class="details">
              <div>
                <dt>Identifiant</dt>
                <dd>
                  <code>{{ node.info()?.nodeId }}</code>
                </dd>
              </div>
              <div>
                <dt>Pharmacie</dt>
                <dd>{{ node.info()?.pharmacySlug || '—' }}</dd>
              </div>
              <div>
                <dt>Version</dt>
                <dd>{{ node.info()?.version }}</dd>
              </div>
              <div>
                <dt>Cloud</dt>
                <dd class="break-all">{{ node.info()?.cloudUrl || '—' }}</dd>
              </div>
            </dl>
          </section>

          <section class="card">
            <div class="card-header">
              <h2 class="card-title flex items-center gap-2">
                <app-icon name="monitor" [size]="16" class="text-muted" />
                Ce poste
              </h2>
              @if (outbox.rejectedCount() > 0) {
                <span class="badge badge-danger"
                  ><app-icon name="alert" [size]="12" />{{ outbox.rejectedCount() }} rejet(s)</span
                >
              }
            </div>
            <dl class="details">
              <div>
                <dt>Ventes en attente d'envoi au serveur</dt>
                <dd>
                  <strong [class.text-warning]="outbox.pendingCount() > 0">{{
                    outbox.pendingCount()
                  }}</strong>
                </dd>
              </div>
              <div>
                <dt>Ventes rejetées</dt>
                <dd>
                  <strong [class.text-danger]="outbox.rejectedCount() > 0">{{
                    outbox.rejectedCount()
                  }}</strong>
                </dd>
              </div>
              <div>
                <dt>Dernière synchro</dt>
                <dd>
                  {{ outbox.lastSyncAt() ? (outbox.lastSyncAt() | date: 'dd/MM HH:mm') : '—' }}
                </dd>
              </div>
            </dl>
          </section>
        </div>

        @if (terminals(); as board) {
          <app-terminal-board
            [board]="board"
            [now]="now()"
            [current]="terminal.terminalId"
            [canForget]="canForget()"
            [busy]="busy()"
            (forget)="forget($event)"
            (forgetStale)="forgetStale()"
          />
        }
      </div>
    </div>
  `,
  styles: `
    .details > div {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 1rem;
      padding: calc(0.75rem * var(--density)) calc(1.25rem * var(--density));
      font-size: 0.875rem;
    }
    .details > div + div {
      border-top: 1px solid var(--border);
    }
    .details dt {
      color: var(--text-muted);
    }
    .details dd {
      min-width: 0;
      text-align: right;
      font-weight: 500;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class SyncStatusPage {
  protected readonly node = inject(NodeInfoService);
  protected readonly outbox = inject(SaleOutboxService);
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  protected readonly terminal = inject(TerminalService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  protected readonly status = signal<SyncStatus | null>(null);
  protected readonly terminals = signal<TerminalBoard | null>(null);
  protected readonly now = signal(Date.now());
  protected readonly busy = signal(false);
  /** Forgetting writes to the hub: refused through the cloud for a pharmacy served by a hub. */
  protected readonly canForget = computed(
    () => this.permissions.has('HUB_ADMIN') && this.terminals()?.relayedByHub === false,
  );

  constructor() {
    void this.refresh();
    void this.refreshTerminals();
    const timer = setInterval(() => void this.refresh(), 10_000);
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(() => clearInterval(timer));
    whileVisible(() => void this.refreshTerminals(), 30_000, destroyRef);
    whileVisible(() => this.now.set(Date.now()), 15_000, destroyRef);
  }

  protected async forget(terminal: TerminalView): Promise<void> {
    const pending =
      terminal.pendingSales > 0
        ? ` Ses ${plural(terminal.pendingSales, 'vente', 'ventes')} en attente restent sur le poste et seront envoyées s'il revient.`
        : '';
    if (!confirm(`Oublier le poste ${terminal.terminalId} ?${pending}`)) {
      return;
    }
    await this.act(
      () =>
        this.http.delete<void>(
          `${this.env.apiUrl}/v1/pos/terminals/${encodeURIComponent(terminal.terminalId)}`,
        ),
      () => `Poste ${terminal.terminalId} oublié`,
    );
  }

  protected async forgetStale(): Promise<void> {
    if (
      !confirm(
        'Oublier tous les postes hors ligne sans vente en attente ? Un poste qui revient réapparaît à son prochain signal.',
      )
    ) {
      return;
    }
    await this.act(
      () =>
        this.http.post<{ forgotten: number }>(
          `${this.env.apiUrl}/v1/pos/terminals/forget-stale`,
          {},
        ),
      (result) => plural(result?.forgotten ?? 0, 'poste oublié', 'postes oubliés'),
    );
  }

  protected async syncNow(): Promise<void> {
    this.status.set(
      await firstValueFrom(this.http.post<SyncStatus>(`${this.env.apiUrl}/v1/hub/sync/run`, {})),
    );
  }

  private async refreshTerminals(): Promise<void> {
    try {
      this.terminals.set(
        await firstValueFrom(this.http.get<TerminalBoard>(`${this.env.apiUrl}/v1/pos/terminals`)),
      );
      this.now.set(Date.now());
    } catch {
      // no permission or server unreachable: the section stays hidden or shows the last state
    }
  }

  private async act<T>(
    request: () => Observable<T>,
    success: (result: T) => string,
  ): Promise<void> {
    this.busy.set(true);
    try {
      this.toast.success(success(await firstValueFrom(request())));
      await this.refreshTerminals();
    } catch (error) {
      this.toast.error(
        (error as { error?: { detail?: string } })?.error?.detail ??
          'Action refusée par le serveur',
      );
    } finally {
      this.busy.set(false);
    }
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
