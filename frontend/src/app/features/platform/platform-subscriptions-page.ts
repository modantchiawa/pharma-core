import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import {
  SubscriptionPaymentMethod,
  SubscriptionPlan,
  TenantSubscription,
  TenantSubscriptionDetail,
} from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import {
  PAYMENT_METHOD_LABELS,
  ago,
  statusLabel,
  statusTone,
} from '../../core/subscription/license-text';
import { daysLeft } from '../../core/subscription/license';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';

type Filter = 'ALL' | 'DUE' | 'LATE' | 'BLOCKED';

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'ALL', label: 'Toutes' },
  { value: 'DUE', label: 'À renouveler' },
  { value: 'LATE', label: 'En retard' },
  { value: 'BLOCKED', label: 'Bloquées' },
];

/**
 * Vendor console: subscription of every client pharmacy, payments received outside the
 * application, plan changes, suspension and reactivation. Opens on {@code ?tenant=<id>}.
 */
@Component({
  selector: 'app-platform-subscriptions-page',
  imports: [DatePipe, FormsModule, MoneyPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'close()' },
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Abonnements</h1>
          <p class="page-subtitle">
            Formules, règlements reçus et licences des pharmacies clientes.
          </p>
        </div>
      </header>

      <div class="mb-6 grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="checkCircle" [size]="16" /></span>
            À jour
          </div>
          <div class="kpi-value">{{ count('ACTIVE') }}</div>
          <div class="kpi-foot">sur {{ rows().length }} pharmacie(s)</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
            À renouveler
          </div>
          <div class="kpi-value" [class.text-warning]="count('WARNING') > 0">
            {{ count('WARNING') }}
          </div>
          <div class="kpi-foot">Échéance dans les 7 jours</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="clock" [size]="16" /></span>
            En retard
          </div>
          <div class="kpi-value" [class.text-danger]="count('GRACE') > 0">
            {{ count('GRACE') }}
          </div>
          <div class="kpi-foot">Délai de grâce de 3 jours</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="ban" [size]="16" /></span>
            Bloquées
          </div>
          <div class="kpi-value" [class.text-danger]="count('BLOCKED') > 0">
            {{ count('BLOCKED') }}
          </div>
          <div class="kpi-foot">Suspendues ou expirées</div>
        </div>
      </div>

      <section class="card overflow-hidden">
        <div class="card-header flex-wrap gap-3">
          <div
            class="segmented max-w-full overflow-x-auto"
            role="tablist"
            aria-label="Filtrer les pharmacies"
          >
            @for (f of filters; track f.value) {
              <button
                type="button"
                role="tab"
                class="whitespace-nowrap"
                [attr.aria-selected]="filter() === f.value"
                (click)="filter.set(f.value)"
              >
                {{ f.label }}
              </button>
            }
          </div>
          <label class="input-with-icon w-full sm:ml-auto sm:w-64">
            <app-icon name="search" [size]="16" />
            <input
              class="input"
              type="search"
              placeholder="Pharmacie ou identifiant"
              [ngModel]="query()"
              (ngModelChange)="query.set($event)"
              aria-label="Rechercher une pharmacie"
            />
          </label>
        </div>
        <div class="overflow-x-auto">
          <table class="data-table [&_th]:whitespace-nowrap">
            <thead>
              <tr>
                <th>Pharmacie</th>
                <th>Formule</th>
                <th>Statut</th>
                <th>Payé jusqu'au</th>
                <th class="num">Jours</th>
                <th>Dernier règlement</th>
                <th>Licence</th>
              </tr>
            </thead>
            <tbody>
              @for (t of visible(); track t.tenantId) {
                <tr
                  class="cursor-pointer hover:bg-surface-2"
                  [class.bg-brand-soft]="selectedId() === t.tenantId"
                  tabindex="0"
                  (click)="open(t.tenantId)"
                  (keydown.enter)="open(t.tenantId)"
                  [attr.data-testid]="'subscription-row-' + t.slug"
                >
                  <td>
                    <div class="flex items-center gap-3">
                      <span
                        class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand-soft text-xs font-bold text-brand"
                        >{{ t.slug.charAt(0).toUpperCase() }}</span
                      >
                      <span class="min-w-0">
                        <span class="block font-medium">{{ t.legalName }}</span>
                        <small class="block text-xs text-subtle"
                          >{{ t.slug }} ·
                          {{ t.topology === 'HUB' ? 'serveur local' : 'cloud' }}</small
                        >
                      </span>
                    </div>
                  </td>
                  <td class="whitespace-nowrap">
                    <span class="font-medium">{{ t.planName ?? t.plan }}</span>
                    <small class="block text-xs text-subtle"
                      >{{ t.monthlyPriceXof | money: 'XOF' }} / mois</small
                    >
                  </td>
                  <td>
                    <span
                      class="badge whitespace-nowrap"
                      [class.badge-success]="tone(t) === 'success'"
                      [class.badge-warning]="tone(t) === 'warning'"
                      [class.badge-danger]="tone(t) === 'danger'"
                      ><span class="dot"></span>{{ label(t) }}</span
                    >
                  </td>
                  <td class="whitespace-nowrap">
                    {{ t.paidUntil ? (t.paidUntil | date: 'dd/MM/yyyy') : 'Sans échéance' }}
                  </td>
                  <td class="num">
                    @if (remaining(t); as days) {
                      <span [class.text-warning]="days <= 7">{{ days }}</span>
                    } @else if (t.paidUntil) {
                      <span class="badge badge-danger">échu</span>
                    } @else {
                      <span class="text-subtle">—</span>
                    }
                  </td>
                  <td class="whitespace-nowrap text-muted">
                    @if (t.lastPaymentOn) {
                      {{ t.lastPaymentOn | date: 'dd/MM/yyyy' }} ·
                      {{ t.lastPaymentXof | money: 'XOF' }}
                    } @else {
                      —
                    }
                  </td>
                  <td class="whitespace-nowrap text-muted">
                    @if (t.license.issuedAt) {
                      n° {{ t.license.serial }} · {{ ago(t.license.issuedAt) }}
                    } @else {
                      —
                    }
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="7">
                    <div class="empty-state">
                      <app-icon name="building" [size]="22" />
                      <p>Aucune pharmacie ne correspond.</p>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>

    @if (detail(); as d) {
      <div
        class="fixed inset-0 z-40 bg-[rgb(2_6_12/0.45)] backdrop-blur-[2px]"
        aria-hidden="true"
        (click)="close()"
      ></div>
      <aside
        class="fixed inset-y-0 right-0 z-50 flex w-full flex-col border-l border-line bg-surface shadow-[var(--shadow-lg)] sm:max-w-xl animate-in"
        role="dialog"
        aria-modal="true"
        data-side-panel
        [attr.aria-label]="'Abonnement ' + d.subscription.legalName"
      >
        <header class="flex items-start gap-3 border-b border-line px-4 py-3 sm:px-5">
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="truncate text-base font-semibold">{{ d.subscription.legalName }}</h2>
              <span
                class="badge"
                [class.badge-success]="tone(d.subscription) === 'success'"
                [class.badge-warning]="tone(d.subscription) === 'warning'"
                [class.badge-danger]="tone(d.subscription) === 'danger'"
                ><span class="dot"></span>{{ label(d.subscription) }}</span
              >
            </div>
            <p class="mt-0.5 text-sm text-muted">
              {{ d.subscription.planName ?? d.subscription.plan }} ·
              {{
                d.subscription.paidUntil
                  ? 'payé jusqu’au ' + (d.subscription.paidUntil | date: 'd MMMM y')
                  : 'sans échéance'
              }}
            </p>
          </div>
          <button
            type="button"
            class="btn btn-ghost btn-icon"
            aria-label="Fermer"
            (click)="close()"
          >
            <app-icon name="x" [size]="18" />
          </button>
        </header>

        <div class="flex-1 space-y-6 overflow-y-auto px-4 py-4 sm:px-5">
          @if (d.subscription.suspendedAt) {
            <div class="alert alert-danger" role="status">
              <app-icon name="ban" [size]="18" />
              <span>
                Suspendue le {{ d.subscription.suspendedAt | date: 'd MMMM y à HH:mm' }}
                @if (d.subscription.suspensionReason) {
                  · {{ d.subscription.suspensionReason }}
                }
              </span>
            </div>
          }

          <section aria-label="Enregistrer un règlement">
            <h3 class="mb-3 text-sm font-semibold">Enregistrer un règlement</h3>
            <form class="grid gap-3 sm:grid-cols-2" (ngSubmit)="recordPayment()">
              <div>
                <label class="label" for="months">Durée payée</label>
                <select
                  id="months"
                  class="select"
                  name="months"
                  [ngModel]="months()"
                  (ngModelChange)="setMonths($event)"
                >
                  @for (m of monthChoices; track m) {
                    <option [ngValue]="m">{{ m }} mois</option>
                  }
                </select>
              </div>
              <div>
                <label class="label" for="amount">Montant reçu (F CFA)</label>
                <input
                  id="amount"
                  class="input num"
                  name="amount"
                  inputmode="numeric"
                  [ngModel]="amount()"
                  (ngModelChange)="amount.set($event)"
                  data-testid="payment-amount"
                />
                <p class="hint">{{ planPrice() | money: 'XOF' }} × {{ months() }} mois</p>
              </div>
              <div>
                <label class="label" for="method">Mode</label>
                <select
                  id="method"
                  class="select"
                  name="method"
                  [ngModel]="method()"
                  (ngModelChange)="method.set($event)"
                >
                  @for (m of methodChoices; track m) {
                    <option [ngValue]="m">{{ methods[m] }}</option>
                  }
                </select>
              </div>
              <div>
                <label class="label" for="paidAt">Reçu le</label>
                <input
                  id="paidAt"
                  type="date"
                  class="input"
                  name="paidAt"
                  [max]="today"
                  [ngModel]="paidAt()"
                  (ngModelChange)="paidAt.set($event)"
                />
              </div>
              <div class="sm:col-span-2">
                <label class="label" for="reference">Référence</label>
                <input
                  id="reference"
                  class="input"
                  name="reference"
                  maxlength="80"
                  placeholder="N° de transaction, de virement ou de reçu"
                  [ngModel]="reference()"
                  (ngModelChange)="reference.set($event)"
                />
              </div>
              <div class="sm:col-span-2">
                <label class="label" for="note">Note</label>
                <input
                  id="note"
                  class="input"
                  name="note"
                  maxlength="255"
                  [ngModel]="note()"
                  (ngModelChange)="note.set($event)"
                />
                <p class="hint">
                  Prolonge la période payée à partir du
                  {{ extendFrom() | date: 'd MMMM y' }} jusqu'au
                  {{ extendTo() | date: 'd MMMM y' }}.
                </p>
              </div>
              <div class="sm:col-span-2">
                <button
                  type="submit"
                  class="btn btn-primary w-full sm:w-auto"
                  [disabled]="busy() || amountValue() <= 0"
                >
                  <app-icon name="banknote" [size]="16" />
                  Enregistrer le règlement
                </button>
              </div>
            </form>
          </section>

          <section aria-label="Formule" class="border-t border-line pt-5">
            <h3 class="mb-3 text-sm font-semibold">Formule</h3>
            <div class="flex flex-col gap-2 sm:flex-row">
              <select
                class="select flex-1"
                aria-label="Formule"
                [ngModel]="plan()"
                (ngModelChange)="plan.set($event)"
              >
                @for (p of plans(); track p.code) {
                  <option [ngValue]="p.code">
                    {{ p.name }} · {{ p.monthlyPriceXof | money: 'XOF' }} / mois ·
                    {{ p.maxTerminals }} postes
                  </option>
                }
              </select>
              <button
                type="button"
                class="btn btn-secondary"
                [disabled]="busy() || plan() === d.subscription.plan"
                (click)="changePlan()"
              >
                Changer de formule
              </button>
            </div>
          </section>

          <section aria-label="Suspension" class="border-t border-line pt-5">
            <h3 class="mb-3 text-sm font-semibold">Accès de la pharmacie</h3>
            @if (d.subscription.tenantStatus === 'SUSPENDED') {
              <p class="mb-3 text-sm text-muted">
                Ventes et modifications bloquées ; la consultation et les exports restent ouverts.
              </p>
              <button
                type="button"
                class="btn btn-primary"
                [disabled]="busy()"
                (click)="reactivate()"
              >
                <app-icon name="checkCircle" [size]="16" />
                Réactiver la pharmacie
              </button>
            } @else {
              <div class="flex flex-col gap-2 sm:flex-row">
                <input
                  class="input flex-1"
                  aria-label="Motif de la suspension"
                  placeholder="Motif (ex. deux mois impayés)"
                  maxlength="255"
                  [ngModel]="reason()"
                  (ngModelChange)="reason.set($event)"
                />
                <button
                  type="button"
                  class="btn btn-danger"
                  [disabled]="busy() || !reason().trim()"
                  (click)="suspend()"
                >
                  <app-icon name="ban" [size]="16" />
                  Suspendre
                </button>
              </div>
              <p class="hint">
                Effet immédiat sur le cloud, à la prochaine synchronisation pour un serveur local.
              </p>
            }
          </section>

          <section aria-label="Licence" class="border-t border-line pt-5">
            <h3 class="mb-3 text-sm font-semibold">Licence</h3>
            <dl class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <dt class="text-muted">Dernière émission</dt>
              <dd>
                {{
                  d.subscription.license.issuedAt
                    ? (d.subscription.license.issuedAt | date: 'dd/MM/yyyy HH:mm') +
                      ' · n° ' +
                      d.subscription.license.serial
                    : '—'
                }}
              </dd>
              <dt class="text-muted">Valable hors ligne jusqu'au</dt>
              <dd>
                {{
                  d.subscription.license.validUntil
                    ? (d.subscription.license.validUntil | date: 'dd/MM/yyyy HH:mm')
                    : '—'
                }}
              </dd>
              <dt class="text-muted">Dernier contact du serveur</dt>
              <dd>
                {{
                  d.subscription.lastHubContactAt
                    ? ago(d.subscription.lastHubContactAt)
                    : d.subscription.topology === 'HUB'
                      ? 'jamais'
                      : 'sans serveur local'
                }}
              </dd>
            </dl>
          </section>

          <section aria-label="Historique des règlements" class="border-t border-line pt-5">
            <h3 class="mb-3 text-sm font-semibold">Historique des règlements</h3>
            <ul class="divide-y divide-[var(--border)]">
              @for (p of d.payments; track p.id) {
                <li class="flex items-start justify-between gap-3 py-2.5 text-sm">
                  <span class="min-w-0">
                    <span class="block font-medium"
                      >{{ p.paidAt | date: 'dd/MM/yyyy' }} · {{ methods[p.method] }}</span
                    >
                    <span class="block text-xs text-subtle"
                      >{{ p.reference ?? 'sans référence' }} · {{ p.months }} mois jusqu'au
                      {{ p.periodTo | date: 'dd/MM/yyyy' }} · {{ p.recordedBy }}</span
                    >
                  </span>
                  <span class="num shrink-0 font-semibold">{{ p.amountXof | money: 'XOF' }}</span>
                </li>
              } @empty {
                <li class="py-2 text-sm text-muted">Aucun règlement enregistré.</li>
              }
            </ul>
          </section>
        </div>
      </aside>
    }
  `,
})
export class PlatformSubscriptionsPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/platform/subscriptions`;
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);

  protected readonly filters = FILTERS;
  protected readonly methods = PAYMENT_METHOD_LABELS;
  protected readonly methodChoices = Object.keys(
    PAYMENT_METHOD_LABELS,
  ) as SubscriptionPaymentMethod[];
  protected readonly monthChoices = [1, 2, 3, 6, 12];
  protected readonly today = new Date().toISOString().slice(0, 10);

  protected readonly rows = signal<TenantSubscription[]>([]);
  protected readonly plans = signal<SubscriptionPlan[]>([]);
  protected readonly filter = signal<Filter>('ALL');
  protected readonly query = signal('');
  protected readonly selectedId = signal<string | null>(null);
  protected readonly detail = signal<TenantSubscriptionDetail | null>(null);
  protected readonly busy = signal(false);

  protected readonly months = signal(1);
  protected readonly amount = signal('');
  protected readonly method = signal<SubscriptionPaymentMethod>('MOBILE_MONEY');
  protected readonly paidAt = signal(this.today);
  protected readonly reference = signal('');
  protected readonly note = signal('');
  protected readonly plan = signal('');
  protected readonly reason = signal('');

  protected readonly visible = computed(() => {
    const q = normalize(this.query());
    return this.rows().filter(
      (t) => matches(t, this.filter()) && (!q || normalize(`${t.legalName} ${t.slug}`).includes(q)),
    );
  });
  protected readonly planPrice = computed(() => {
    const code = this.detail()?.subscription.plan;
    return this.plans().find((p) => p.code === code)?.monthlyPriceXof ?? 0;
  });
  protected readonly amountValue = computed(
    () => Number(String(this.amount()).replace(/\s/g, '')) || 0,
  );
  protected readonly extendFrom = computed(() => {
    const paidUntil = this.detail()?.subscription.paidUntil;
    const now = Date.now();
    return new Date(paidUntil && Date.parse(paidUntil) > now ? Date.parse(paidUntil) : now);
  });
  protected readonly extendTo = computed(() => {
    const to = new Date(this.extendFrom());
    to.setMonth(to.getMonth() + this.months());
    return to;
  });

  constructor() {
    void this.refresh();
    void firstValueFrom(this.http.get<SubscriptionPlan[]>(`${this.api}/plans`)).then((plans) =>
      this.plans.set(plans),
    );
    this.route.queryParamMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      const tenant = params.get('tenant');
      if (tenant && tenant !== this.selectedId()) {
        void this.load(tenant);
      } else if (!tenant && this.selectedId()) {
        this.selectedId.set(null);
        this.detail.set(null);
      }
    });
  }

  protected count(status: TenantSubscription['state']['status']): number {
    return this.rows().filter((t) => t.state.status === status).length;
  }

  protected label(t: TenantSubscription): string {
    return statusLabel(t.state);
  }

  protected tone(t: TenantSubscription): 'success' | 'warning' | 'danger' {
    return statusTone(t.state);
  }

  protected remaining(t: TenantSubscription): number | null {
    const days = daysLeft(t.paidUntil, Date.now());
    return days && days > 0 ? days : null;
  }

  protected ago(iso: string | null): string {
    return ago(iso, Date.now());
  }

  protected open(tenantId: string): void {
    void this.router.navigate([], { queryParams: { tenant: tenantId }, replaceUrl: true });
  }

  protected close(): void {
    if (this.selectedId()) {
      void this.router.navigate([], { queryParams: {}, replaceUrl: true });
    }
  }

  protected setMonths(months: number): void {
    this.months.set(months);
    this.amount.set(String(this.planPrice() * months));
  }

  protected async recordPayment(): Promise<void> {
    const id = this.selectedId();
    if (!id) {
      return;
    }
    await this.act(
      this.http.post<TenantSubscriptionDetail>(`${this.api}/${id}/payments`, {
        amountXof: this.amountValue(),
        method: this.method(),
        reference: this.reference() || null,
        months: this.months(),
        paidAt: this.paidAt() || null,
        note: this.note() || null,
      }),
      (detail) =>
        `Règlement enregistré : payé jusqu'au ${new Date(detail.subscription.paidUntil ?? '').toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })}`,
    );
  }

  protected async changePlan(): Promise<void> {
    const id = this.selectedId();
    if (id) {
      await this.act(
        this.http.put<TenantSubscriptionDetail>(`${this.api}/${id}/plan`, { plan: this.plan() }),
        () => 'Formule modifiée',
      );
    }
  }

  protected async suspend(): Promise<void> {
    const id = this.selectedId();
    const name = this.detail()?.subscription.legalName;
    if (id && window.confirm(`Suspendre ${name} ? Ventes et modifications seront bloquées.`)) {
      await this.act(
        this.http.post<TenantSubscriptionDetail>(`${this.api}/${id}/suspension`, {
          reason: this.reason().trim(),
        }),
        () => 'Pharmacie suspendue',
      );
    }
  }

  protected async reactivate(): Promise<void> {
    const id = this.selectedId();
    if (id) {
      await this.act(
        this.http.post<TenantSubscriptionDetail>(`${this.api}/${id}/reactivation`, {}),
        () => 'Pharmacie réactivée',
      );
    }
  }

  private async act(
    request: Observable<TenantSubscriptionDetail>,
    success: (detail: TenantSubscriptionDetail) => string,
  ): Promise<void> {
    this.busy.set(true);
    try {
      const detail = await firstValueFrom(request);
      this.show(detail);
      this.toasts.success(success(detail));
      await this.refresh();
    } catch (error) {
      const detail = (error as HttpErrorResponse).error?.detail as string | undefined;
      this.toasts.error(detail ?? 'Opération refusée');
    } finally {
      this.busy.set(false);
    }
  }

  private async load(tenantId: string): Promise<void> {
    this.selectedId.set(tenantId);
    try {
      this.show(
        await firstValueFrom(this.http.get<TenantSubscriptionDetail>(`${this.api}/${tenantId}`)),
      );
    } catch {
      this.toasts.error('Pharmacie introuvable');
      this.close();
    }
  }

  private show(detail: TenantSubscriptionDetail): void {
    this.detail.set(detail);
    this.plan.set(detail.subscription.plan);
    this.reason.set('');
    this.reference.set('');
    this.note.set('');
    this.paidAt.set(this.today);
    this.months.set(1);
    const price = this.plans().find((p) => p.code === detail.subscription.plan)?.monthlyPriceXof;
    this.amount.set(String(price ?? detail.subscription.monthlyPriceXof));
  }

  private async refresh(): Promise<void> {
    this.rows.set(await firstValueFrom(this.http.get<TenantSubscription[]>(this.api)));
  }
}

function matches(t: TenantSubscription, filter: Filter): boolean {
  switch (filter) {
    case 'ALL':
      return true;
    case 'DUE':
      return t.state.status === 'WARNING';
    case 'LATE':
      return t.state.status === 'GRACE';
    case 'BLOCKED':
      return t.state.status === 'BLOCKED';
  }
}

function normalize(value: string): string {
  return value
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim();
}
