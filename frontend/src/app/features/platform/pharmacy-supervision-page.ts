import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { PharmacySupervision, SupervisedAccount, TerminalView } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { badgeClass, hubBadge, plural, relativeTime } from '../../shared/terminal-sync';
import { Icon } from '../../shared/ui/icon';
import { TerminalBoardView } from '../../shared/ui/terminal-board';
import { whileVisible } from '../../shared/visible-interval';

const REFRESH_MS = 30_000;
const TICK_MS = 15_000;
const DAY_MS = 86_400_000;

/** Vendor console: one pharmacy's hub link, cash registers, accounts and subscription. */
@Component({
  selector: 'app-pharmacy-supervision-page',
  imports: [DatePipe, RouterLink, Icon, TerminalBoardView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <a routerLink="/platform" class="btn btn-ghost btn-sm -ml-2 mb-3">
        <app-icon name="chevronLeft" [size]="16" />
        Supervision
      </a>

      @if (data(); as d) {
        <header class="page-header">
          <div class="flex min-w-0 items-center gap-3">
            <span
              class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-brand-soft text-lg font-bold text-brand"
              aria-hidden="true"
              >{{ d.pharmacy.slug.charAt(0).toUpperCase() }}</span
            >
            <div class="min-w-0">
              <h1 class="page-title flex flex-wrap items-center gap-x-3 gap-y-1">
                <span class="min-w-0 break-words">{{
                  d.pharmacy.displayName || d.pharmacy.legalName
                }}</span>
                <span
                  class="badge"
                  [class.badge-success]="d.pharmacy.status === 'ACTIVE'"
                  [class.badge-danger]="d.pharmacy.status !== 'ACTIVE'"
                  ><span class="dot"></span>{{ statusLabel(d.pharmacy.status) }}</span
                >
              </h1>
              <p class="page-subtitle">
                {{ d.pharmacy.legalName }} · <code>{{ d.pharmacy.slug }}</code>
                @if (d.pharmacy.city) {
                  · {{ d.pharmacy.city }}
                }
                · {{ d.pharmacy.topology === 'HUB' ? 'serveur local en officine' : 'cloud direct' }}
              </p>
            </div>
          </div>
          <button
            type="button"
            class="btn btn-secondary"
            (click)="refresh()"
            [disabled]="loading()"
          >
            <app-icon name="refresh" [size]="16" [class.animate-spin]="loading()" />
            Actualiser
          </button>
        </header>

        <div class="space-y-6">
          <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            <section class="card" aria-labelledby="hub-title">
              <div class="card-header">
                <h2 id="hub-title" class="card-title flex items-center gap-2">
                  <app-icon name="server" [size]="16" class="text-muted" />
                  Serveur local
                </h2>
                <span [class]="badgeClass(hub().tone)"
                  ><app-icon [name]="hub().icon" [size]="12" />{{ hub().label }}</span
                >
              </div>
              @if (d.hub; as h) {
                <dl class="details">
                  <div>
                    <dt>Dernier contact</dt>
                    <dd [attr.title]="h.lastContactAt | date: 'dd/MM/yyyy HH:mm:ss'">
                      {{ ago(h.lastContactAt) }}
                    </dd>
                  </div>
                  <div>
                    <dt>Dernier envoi vers le cloud</dt>
                    <dd [attr.title]="h.lastPushAt | date: 'dd/MM/yyyy HH:mm:ss'">
                      {{ ago(h.lastPushAt) }}
                    </dd>
                  </div>
                  <div>
                    <dt>Version</dt>
                    <dd>{{ h.appVersion || '—' }}</dd>
                  </div>
                  <div>
                    <dt>Identifiant</dt>
                    <dd class="truncate">
                      <code>{{ h.nodeId }}</code>
                    </dd>
                  </div>
                  <div>
                    <dt>Appairé le</dt>
                    <dd>{{ h.pairedAt | date: 'dd/MM/yyyy' }}</dd>
                  </div>
                </dl>
              } @else {
                <div class="card-body text-sm text-muted">
                  @if (d.pharmacy.topology === 'HUB') {
                    Mode serveur local, mais aucun serveur n'est appairé : générez un code
                    d'appairage depuis la liste.
                  } @else {
                    Les caisses travaillent directement avec le cloud ; chaque poste garde ses
                    ventes hors ligne dans son propre journal jusqu'au retour du réseau.
                  }
                </div>
              }
            </section>

            <section class="card" aria-labelledby="subscription-title">
              <div class="card-header">
                <h2 id="subscription-title" class="card-title flex items-center gap-2">
                  <app-icon name="card" [size]="16" class="text-muted" />
                  Abonnement
                </h2>
                <span class="badge badge-brand">{{ d.pharmacy.subscriptionPlan }}</span>
              </div>
              <dl class="details">
                <div>
                  <dt>Statut</dt>
                  <dd>{{ statusLabel(d.pharmacy.status) }}</dd>
                </div>
                <div>
                  <dt>Échéance</dt>
                  <dd>
                    @if (d.pharmacy.subscriptionExpiresAt) {
                      {{ d.pharmacy.subscriptionExpiresAt | date: 'dd/MM/yyyy' }}
                      <span
                        class="ml-1 text-xs"
                        [class.text-muted]="expiresInDays()! > 30"
                        [class.text-warning]="expiresInDays()! <= 30 && expiresInDays()! >= 0"
                        [class.text-danger]="expiresInDays()! < 0"
                        >{{ expiryLabel() }}</span
                      >
                    } @else {
                      Sans échéance
                    }
                  </dd>
                </div>
                <div>
                  <dt>Cliente depuis</dt>
                  <dd>{{ d.pharmacy.createdAt | date: 'MMMM yyyy' }}</dd>
                </div>
              </dl>
              <div class="border-t border-line px-5 py-3">
                <a
                  routerLink="/platform/subscriptions"
                  [queryParams]="{ tenant: d.pharmacy.id }"
                  class="btn btn-soft btn-sm"
                >
                  Gérer l'abonnement
                  <app-icon name="arrowRight" [size]="14" />
                </a>
              </div>
            </section>

            <section class="card kpi md:col-span-2 xl:col-span-1" aria-labelledby="sync-title">
              <div id="sync-title" class="kpi-label">
                <span
                  class="kpi-icon"
                  [class.bg-[var(--danger-soft)]]="d.terminals.counts.stale > 0"
                  [class.text-danger]="d.terminals.counts.stale > 0"
                  ><app-icon name="activity" [size]="16"
                /></span>
                Synchronisation des postes
              </div>
              <div class="kpi-value">
                {{ d.terminals.counts.synced }}
                <span class="text-base font-semibold text-subtle"
                  >/ {{ d.terminals.counts.total }} synchronisés</span
                >
              </div>
              <ul class="mt-3 grid grid-cols-3 gap-2 text-center text-xs">
                <li class="rounded-[var(--radius-sm)] bg-surface-2 px-2 py-2">
                  <strong
                    class="block text-lg"
                    [class.text-warning]="d.terminals.counts.pendingSales > 0"
                    >{{ d.terminals.counts.pendingSales }}</strong
                  >
                  <span class="text-muted">ventes en attente</span>
                </li>
                <li class="rounded-[var(--radius-sm)] bg-surface-2 px-2 py-2">
                  <strong
                    class="block text-lg"
                    [class.text-danger]="d.terminals.counts.stale > 0"
                    >{{ d.terminals.counts.stale }}</strong
                  >
                  <span class="text-muted">hors ligne</span>
                </li>
                <li class="rounded-[var(--radius-sm)] bg-surface-2 px-2 py-2">
                  <strong class="block text-lg">{{ d.terminals.counts.never }}</strong>
                  <span class="text-muted">jamais synchronisés</span>
                </li>
              </ul>
            </section>
          </div>

          <app-terminal-board
            [board]="d.terminals"
            [now]="now()"
            [canForget]="d.pharmacy.topology === 'CLOUD'"
            [busy]="busy()"
            (forget)="forget($event)"
            (forgetStale)="forgetStale()"
          />

          <section class="card overflow-hidden" aria-labelledby="accounts-title">
            <div class="card-header">
              <h2 id="accounts-title" class="card-title flex items-center gap-2">
                <app-icon name="customers" [size]="16" class="text-muted" />
                Comptes
                <span class="badge">{{ d.accounts.length }}</span>
              </h2>
              <span class="text-xs text-muted">{{ activeAccounts() }}</span>
            </div>
            <div class="hidden overflow-x-auto sm:block">
              <table class="data-table [&_th]:whitespace-nowrap">
                <thead>
                  <tr>
                    <th>Nom</th>
                    <th>Rôles</th>
                    <th>État</th>
                    <th>PIN</th>
                    <th>Dernière connexion</th>
                  </tr>
                </thead>
                <tbody>
                  @for (a of d.accounts; track a.id) {
                    <tr [class.opacity-60]="!a.active">
                      <td>
                        <span class="block font-medium">{{ a.displayName }}</span>
                        <small class="block text-xs text-subtle">{{ a.email }}</small>
                      </td>
                      <td>
                        <span class="flex flex-wrap gap-1">
                          @for (r of a.roles; track r.code) {
                            <span class="badge">{{ r.name }}</span>
                          } @empty {
                            <span class="text-subtle">—</span>
                          }
                        </span>
                      </td>
                      <td>
                        <span [class]="badgeClass(accountState(a).tone)">{{
                          accountState(a).label
                        }}</span>
                      </td>
                      <td>
                        @if (a.pinSet) {
                          <span class="inline-flex items-center gap-1 text-success"
                            ><app-icon name="check" [size]="14" />Défini</span
                          >
                        } @else {
                          <span class="text-subtle">Non défini</span>
                        }
                      </td>
                      <td
                        class="whitespace-nowrap"
                        [attr.title]="a.lastLoginAt | date: 'dd/MM/yyyy HH:mm'"
                      >
                        {{ ago(a.lastLoginAt) }}
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <ul class="divide-y divide-[var(--border)] sm:hidden">
              @for (a of d.accounts; track a.id) {
                <li class="space-y-1.5 px-4 py-3" [class.opacity-60]="!a.active">
                  <div class="flex items-start justify-between gap-2">
                    <span class="min-w-0">
                      <span class="block truncate font-medium">{{ a.displayName }}</span>
                      <small class="block truncate text-xs text-subtle">{{ a.email }}</small>
                    </span>
                    <span [class]="badgeClass(accountState(a).tone)">{{
                      accountState(a).label
                    }}</span>
                  </div>
                  <div class="flex flex-wrap gap-1">
                    @for (r of a.roles; track r.code) {
                      <span class="badge">{{ r.name }}</span>
                    }
                  </div>
                  <p class="text-xs text-muted">
                    Connexion {{ ago(a.lastLoginAt) }} · PIN
                    {{ a.pinSet ? 'défini' : 'non défini' }}
                  </p>
                </li>
              }
            </ul>
          </section>
        </div>
      } @else if (missing()) {
        <section class="card">
          <div class="empty-state">
            <app-icon name="building" [size]="22" />
            <p class="font-medium text-ink">Pharmacie introuvable</p>
            <p>Elle a peut-être été supprimée, ou le lien est incomplet.</p>
          </div>
        </section>
      } @else {
        <div class="space-y-4" aria-busy="true">
          <div class="skeleton h-8 w-72"></div>
          <div class="grid gap-4 md:grid-cols-3">
            <div class="skeleton h-40"></div>
            <div class="skeleton h-40"></div>
            <div class="skeleton h-40"></div>
          </div>
          <div class="skeleton h-64"></div>
        </div>
      }
    </div>
  `,
  styles: `
    .details > div {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 1rem;
      padding: calc(0.6rem * var(--density)) calc(1.25rem * var(--density));
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
export class PharmacySupervisionPage {
  /** Route parameter `:id`. */
  readonly id = input.required<string>();

  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/platform/tenants`;

  protected readonly data = signal<PharmacySupervision | null>(null);
  protected readonly missing = signal(false);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly now = signal(Date.now());
  protected readonly badgeClass = badgeClass;

  protected readonly hub = computed(() => hubBadge(this.data()?.hub ?? null, this.now()));
  protected readonly expiresInDays = computed(() => {
    const expires = this.data()?.pharmacy.subscriptionExpiresAt;
    return expires ? Math.floor((Date.parse(expires) - this.now()) / DAY_MS) : null;
  });
  protected readonly expiryLabel = computed(() => {
    const days = this.expiresInDays();
    if (days === null) {
      return '';
    }
    return days >= 0 ? `dans ${plural(days, 'jour', 'jours')}` : `expiré depuis ${-days} j`;
  });
  protected readonly activeAccounts = computed(() => {
    const accounts = this.data()?.accounts ?? [];
    return plural(accounts.filter((a) => a.active).length, 'compte actif', 'comptes actifs');
  });

  constructor() {
    effect(() => {
      this.id();
      untracked(() => {
        this.data.set(null);
        void this.refresh();
      });
    });
    const destroyRef = inject(DestroyRef);
    whileVisible(() => void this.refresh(), REFRESH_MS, destroyRef);
    whileVisible(() => this.now.set(Date.now()), TICK_MS, destroyRef);
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'Active';
      case 'SUSPENDED':
        return 'Suspendue';
      case 'TERMINATED':
        return 'Résiliée';
      case 'PROVISIONING':
        return 'En création';
      default:
        return status;
    }
  }

  protected accountState(account: SupervisedAccount): {
    label: string;
    tone: 'success' | 'warning' | 'danger' | 'neutral';
  } {
    if (!account.active) {
      return { label: 'Désactivé', tone: 'neutral' };
    }
    if (account.locked) {
      return { label: 'Verrouillé', tone: 'danger' };
    }
    if (account.mustChangePassword) {
      return { label: 'Mot de passe provisoire', tone: 'warning' };
    }
    return { label: 'Actif', tone: 'success' };
  }

  protected ago(at: string | null): string {
    return relativeTime(at, this.now());
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      this.data.set(
        await firstValueFrom(
          this.http.get<PharmacySupervision>(
            `${this.api}/${encodeURIComponent(this.id())}/supervision`,
          ),
        ),
      );
      this.missing.set(false);
      this.now.set(Date.now());
    } catch (error) {
      if (error instanceof HttpErrorResponse && (error.status === 404 || error.status === 400)) {
        this.missing.set(true);
      } else if (this.data()) {
        this.toast.error('Actualisation impossible, nouvel essai dans 30 secondes');
      }
    } finally {
      this.loading.set(false);
    }
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
          `${this.api}/${this.id()}/terminals/${encodeURIComponent(terminal.terminalId)}`,
        ),
      `Poste ${terminal.terminalId} oublié`,
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
          `${this.api}/${this.id()}/terminals/forget-stale`,
          {},
        ),
      (result) => `${plural(result?.forgotten ?? 0, 'poste oublié', 'postes oubliés')}`,
    );
  }

  private async act<T>(
    request: () => Observable<T>,
    success: string | ((result: T) => string),
  ): Promise<void> {
    this.busy.set(true);
    try {
      const result = await firstValueFrom(request());
      this.toast.success(typeof success === 'string' ? success : success(result));
      await this.refresh();
    } catch (error) {
      this.toast.error(
        (error as { error?: { detail?: string } })?.error?.detail ??
          'Action refusée par le serveur',
      );
    } finally {
      this.busy.set(false);
    }
  }
}
