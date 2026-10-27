import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Dashboard, PurchaseOrderSummary } from '../../core/api/models';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { columnPath, niceCeiling } from '../../shared/ui/chart';
import { Icon } from '../../shared/ui/icon';
import { DailyCountCard } from '../control/daily-count-card';
import { SetupChecklist } from './setup-checklist';

interface Bar {
  date: string;
  netCents: number;
  count: number;
  slotX: number;
  slotWidth: number;
  cx: number;
  top: number;
  path: string;
}

const CHART_HEIGHT = 260;
const PLOT_BOTTOM = 230;
const PLOT_HEIGHT = 210;
const AXIS_WIDTH = 52;

const PRESETS = [
  { days: 0, label: "Aujourd'hui" },
  { days: 6, label: '7 jours' },
  { days: 29, label: '30 jours' },
];

const METHOD_LABELS: Record<string, string> = {
  CASH: 'Espèces',
  MOBILE_MONEY: 'Mobile money',
  CARD: 'Carte',
  CHECK: 'Chèque',
  THIRD_PARTY_PAYER: 'Tiers payant',
  VOUCHER: 'Bon',
  CREDIT: 'Crédit client',
};

/** Steering dashboard: sales, margin, payment mix, best sellers, stock exposure, receivables. */
@Component({
  selector: 'app-dashboard-page',
  imports: [
    FormsModule,
    RouterLink,
    MoneyPipe,
    DatePipe,
    DecimalPipe,
    Icon,
    SetupChecklist,
    DailyCountCard,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in space-y-5">
      <header class="page-header">
        <div>
          <h1 class="page-title">Pilotage</h1>
          <p class="page-subtitle">
            Activité du {{ from | date: 'd MMM' }} au {{ to | date: 'd MMM yyyy' }}
          </p>
        </div>
        <form class="flex flex-wrap items-center gap-2" (ngSubmit)="load()">
          <div class="segmented" role="tablist" aria-label="Période">
            @for (p of presets; track p.days) {
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="activePreset() === p.days"
                (click)="preset(p.days)"
              >
                {{ p.label }}
              </button>
            }
          </div>
          <div class="flex w-full flex-wrap items-center gap-1.5 sm:w-auto">
            <input
              type="date"
              class="input min-w-0 flex-1 sm:!w-auto sm:flex-none"
              aria-label="Du"
              [(ngModel)]="from"
              name="from"
              required
            />
            <app-icon name="arrowRight" [size]="14" class="text-subtle" />
            <input
              type="date"
              class="input min-w-0 flex-1 sm:!w-auto sm:flex-none"
              aria-label="Au"
              [(ngModel)]="to"
              name="to"
              required
            />
            <button type="submit" class="btn btn-primary">Afficher</button>
          </div>
        </form>
      </header>

      <app-setup-checklist />

      <app-daily-count-card [compact]="true" />

      @if ((purchaseOrders()?.overdue ?? 0) > 0) {
        <a
          class="alert alert-warning items-center"
          routerLink="/procurement"
          [queryParams]="{ tab: 'orders', scope: 'OVERDUE' }"
          data-testid="overdue-orders"
        >
          <app-icon name="procurement" [size]="16" />
          <span class="flex-1">
            <strong>{{ purchaseOrders()?.overdue }} commande(s) fournisseur en retard</strong> : la
            date de livraison est passée, relancez le fournisseur.
          </span>
          <span class="inline-flex items-center gap-1 whitespace-nowrap font-medium">
            Voir le suivi <app-icon name="arrowRight" [size]="14" />
          </span>
        </a>
      }

      @if (data(); as d) {
        <section class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="Indicateurs">
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>
              Chiffre d'affaires
            </div>
            <div class="kpi-value">{{ d.sales.netCents | money }}</div>
            <div class="kpi-foot flex items-center gap-1.5">
              <span
                class="badge"
                [class.badge-success]="delta() >= 0"
                [class.badge-danger]="delta() < 0"
              >
                <app-icon [name]="delta() >= 0 ? 'trendUpRight' : 'trendDownRight'" [size]="12" />
                {{ delta() >= 0 ? '+' : '' }}{{ delta() | number: '1.0-0' }} %
              </span>
              vs période précédente
            </div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="trendUp" [size]="16" /></span>
              Marge brute
            </div>
            <div class="kpi-value">{{ d.sales.marginCents | money }}</div>
            <div class="kpi-foot">{{ marginRate() | number: '1.0-1' }} % du CA</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="receipt" [size]="16" /></span>
              Ventes
            </div>
            <div class="kpi-value">{{ d.sales.count }}</div>
            <div class="kpi-foot">
              panier moyen {{ d.sales.averageBasketCents | money }} · {{ d.sales.cancelledCount }}
              annulée(s)
            </div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="credit" [size]="16" /></span>
              Créances clients
            </div>
            <div class="kpi-value">{{ d.receivables.customerCreditCents | money }}</div>
            <div class="kpi-foot">{{ d.receivables.debtors }} débiteur(s)</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="stock" [size]="16" /></span>
              Valeur du stock (coût)
            </div>
            <div class="kpi-value">{{ d.stock.valueAtCostCents | money }}</div>
            <div class="kpi-foot">{{ d.stock.belowThreshold }} produit(s) sous le seuil</div>
          </div>
          <div class="card kpi" [class.watch]="d.stock.expiringLots > 0">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
              Périme sous 90 jours
            </div>
            <div class="kpi-value">{{ d.stock.expiringWithin90DaysCents | money }}</div>
            <div class="kpi-foot">
              {{ d.stock.expiringLots }} lot(s) · {{ d.stock.shortfallProducts }} rupture(s)
            </div>
          </div>
          <div class="card kpi" [class.watch]="d.cash.differenceCents !== 0">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="cash" [size]="16" /></span>
              Écarts de caisse
            </div>
            <div class="kpi-value">{{ d.cash.differenceCents | money }}</div>
            <div class="kpi-foot">{{ d.cash.sessionsClosed }} session(s) clôturée(s)</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="trendDown" [size]="16" /></span>
              Dépenses de caisse
            </div>
            <div class="kpi-value">{{ d.cash.expensesCents | money }}</div>
            <div class="kpi-foot">sur la période</div>
          </div>
        </section>

        <section class="card">
          <div class="card-header">
            <div>
              <h2 class="card-title">Chiffre d'affaires par jour</h2>
              @if (peak(); as top) {
                <p class="mt-0.5 text-xs text-muted">
                  Meilleur jour : {{ top.date | date: 'EEEE d MMMM' }} · {{ top.netCents | money }}
                </p>
              }
            </div>
            <div class="segmented" role="tablist" aria-label="Affichage">
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="view() === 'chart'"
                (click)="view.set('chart')"
              >
                Graphique
              </button>
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="view() === 'table'"
                (click)="view.set('table')"
              >
                Tableau
              </button>
            </div>
          </div>
          <div class="card-body">
            @if (bars().length === 0) {
              <div class="empty-state">
                <app-icon name="activity" [size]="22" />
                <p>Aucune vente sur la période.</p>
              </div>
            } @else if (view() === 'chart') {
              <div class="relative" #chart>
                <svg
                  [attr.width]="chartWidth()"
                  [attr.height]="CHART_HEIGHT"
                  class="block w-full overflow-visible"
                  role="img"
                  aria-label="Chiffre d'affaires par jour"
                  (pointerleave)="hovered.set(null)"
                >
                  @for (tick of ticks(); track tick.value) {
                    <line
                      [attr.x1]="AXIS_WIDTH"
                      [attr.x2]="chartWidth()"
                      [attr.y1]="tick.y"
                      [attr.y2]="tick.y"
                      class="grid-line"
                    />
                    <text
                      [attr.x]="AXIS_WIDTH - 8"
                      [attr.y]="tick.y + 4"
                      text-anchor="end"
                      class="axis-text"
                    >
                      {{ tick.label }}
                    </text>
                  }
                  @for (bar of bars(); track bar.date; let i = $index) {
                    <g
                      class="bar"
                      [class.is-hovered]="hovered()?.date === bar.date"
                      tabindex="0"
                      [attr.aria-label]="
                        (bar.date | date: 'd MMMM') + ' : ' + (bar.netCents | money)
                      "
                      (pointerenter)="hovered.set(bar)"
                      (focus)="hovered.set(bar)"
                      (blur)="hovered.set(null)"
                    >
                      <rect
                        [attr.x]="bar.slotX"
                        y="0"
                        [attr.width]="bar.slotWidth"
                        [attr.height]="PLOT_BOTTOM"
                        class="hit"
                      />
                      <path [attr.d]="bar.path" class="mark" />
                      @if (i % labelEvery() === 0) {
                        <text
                          [attr.x]="bar.cx"
                          [attr.y]="PLOT_BOTTOM + 18"
                          text-anchor="middle"
                          class="axis-text"
                        >
                          {{ bar.date | date: 'dd/MM' }}
                        </text>
                      }
                    </g>
                  }
                </svg>
                @if (hovered(); as bar) {
                  <div
                    class="tooltip"
                    [style.left.px]="bar.cx"
                    [style.top.px]="bar.top"
                    role="status"
                  >
                    <p class="text-sm font-semibold text-ink">{{ bar.netCents | money }}</p>
                    <p class="text-xs text-muted">
                      {{ bar.date | date: 'EEE d MMM' }} · {{ bar.count }} vente(s)
                    </p>
                  </div>
                }
              </div>
            } @else {
              <div class="max-h-80 overflow-auto">
                <table class="data-table">
                  <thead>
                    <tr>
                      <th>Jour</th>
                      <th class="num">Ventes</th>
                      <th class="num">Chiffre d'affaires</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (bar of bars(); track bar.date) {
                      <tr>
                        <td>{{ bar.date | date: 'EEE d MMM' }}</td>
                        <td class="num">{{ bar.count }}</td>
                        <td class="num">{{ bar.netCents | money }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>

        <div class="grid gap-4 lg:grid-cols-2">
          <section class="card">
            <div class="card-header">
              <h2 class="card-title">Encaissements par mode</h2>
              <app-icon name="card" [size]="16" class="text-subtle" />
            </div>
            <div class="card-body space-y-4">
              @for (p of d.payments; track p.method + (p.provider ?? '')) {
                <div>
                  <div class="flex items-baseline justify-between gap-3 text-sm">
                    <span class="font-medium">
                      {{ label(p.method) }}
                      @if (p.provider) {
                        <span class="text-muted">· {{ p.provider }}</span>
                      }
                    </span>
                    <span class="num">
                      {{ p.amountCents | money }}
                      <span class="ml-2 inline-block w-10 text-muted"
                        >{{ share(p.amountCents) | number: '1.0-0' }} %</span
                      >
                    </span>
                  </div>
                  <div class="meter mt-1.5">
                    <span [style.width.%]="share(p.amountCents)"></span>
                  </div>
                </div>
              } @empty {
                <div class="empty-state">
                  <app-icon name="cash" [size]="22" />
                  <p>Aucun encaissement</p>
                </div>
              }
            </div>
          </section>
          <section class="card">
            <div class="card-header">
              <h2 class="card-title">Meilleures ventes</h2>
              <app-icon name="pill" [size]="16" class="text-subtle" />
            </div>
            @if (d.topProducts.length > 0) {
              <div class="overflow-x-auto">
                <table class="data-table">
                  <thead>
                    <tr>
                      <th class="w-10">#</th>
                      <th>Produit</th>
                      <th class="num">Qté</th>
                      <th class="num">CA</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (t of d.topProducts; track t.productId; let i = $index) {
                      <tr>
                        <td>
                          <span class="rank" [class.first]="i < 3">{{ i + 1 }}</span>
                        </td>
                        <td class="font-medium">{{ t.designation }}</td>
                        <td class="num">{{ t.quantity }}</td>
                        <td class="num">{{ t.netCents | money }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <div class="empty-state">
                <app-icon name="pill" [size]="22" />
                <p>Aucune vente</p>
              </div>
            }
          </section>
        </div>
      } @else if (error(); as text) {
        <div class="alert alert-danger" role="alert">
          <app-icon name="error" [size]="18" />
          <span>{{ text }}</span>
        </div>
      } @else {
        <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-hidden="true">
          @for (i of [1, 2, 3, 4, 5, 6, 7, 8]; track i) {
            <div class="card kpi space-y-3">
              <div class="skeleton h-4 w-28"></div>
              <div class="skeleton h-7 w-36"></div>
              <div class="skeleton h-3 w-24"></div>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .kpi.watch {
      border-color: color-mix(in oklab, var(--warning) 55%, var(--border));
    }
    .kpi.watch .kpi-icon {
      background: var(--warning-soft);
      color: var(--warning);
    }
    .grid-line {
      stroke: var(--border);
      stroke-width: 1;
    }
    .axis-text {
      fill: var(--text-subtle);
      font-size: 11px;
      font-variant-numeric: tabular-nums;
    }
    .bar {
      outline: none;
      cursor: default;
    }
    .bar .hit {
      fill: transparent;
    }
    .bar .mark {
      fill: var(--brand-primary);
      transition: fill 120ms ease;
    }
    .bar.is-hovered .mark {
      fill: color-mix(in oklab, var(--brand-primary) 75%, white);
    }
    .bar.is-hovered .hit {
      fill: var(--surface-2);
    }
    .bar:focus-visible .hit {
      stroke: var(--brand-ring);
      stroke-width: 2;
    }
    .tooltip {
      position: absolute;
      transform: translate(-50%, calc(-100% - 10px));
      pointer-events: none;
      white-space: nowrap;
      padding: 0.45rem 0.7rem;
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
    }
    .meter {
      height: 6px;
      border-radius: 999px;
      background: var(--brand-softer);
      overflow: hidden;
    }
    .meter > span {
      display: block;
      height: 100%;
      border-radius: inherit;
      background: var(--brand-primary);
    }
    .rank {
      display: inline-flex;
      width: 1.5rem;
      height: 1.5rem;
      align-items: center;
      justify-content: center;
      border-radius: 999px;
      background: var(--surface-3);
      color: var(--text-muted);
      font-size: 0.75rem;
      font-weight: 650;
    }
    .rank.first {
      background: var(--brand-soft);
      color: var(--brand-text);
    }
  `,
})
export class DashboardPage {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly chart = viewChild<ElementRef<HTMLElement>>('chart');
  protected readonly currency = inject(CurrencyService);
  protected readonly data = signal<Dashboard | null>(null);
  protected readonly purchaseOrders = signal<PurchaseOrderSummary | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly view = signal<'chart' | 'table'>('chart');
  protected readonly hovered = signal<Bar | null>(null);
  protected readonly activePreset = signal<number | null>(29);
  protected readonly chartWidth = signal(760);
  protected readonly presets = PRESETS;
  protected readonly CHART_HEIGHT = CHART_HEIGHT;
  protected readonly PLOT_BOTTOM = PLOT_BOTTOM;
  protected readonly AXIS_WIDTH = AXIS_WIDTH;
  protected from = isoDate(daysAgo(29));
  protected to = isoDate(new Date());

  private readonly scaleMax = computed(() => {
    const d = this.data();
    const peak = d ? Math.max(...d.daily.map((p) => p.netCents), 0) : 0;
    return niceCeiling(peak);
  });

  protected readonly ticks = computed(() => {
    const max = this.scaleMax();
    const compact = new Intl.NumberFormat('fr-FR', {
      notation: 'compact',
      maximumFractionDigits: 1,
    });
    return [0, 1, 2, 3, 4].map((i) => {
      const value = (max / 4) * i;
      return {
        value,
        y: PLOT_BOTTOM - (value / max) * PLOT_HEIGHT,
        label: compact.format(value / 10 ** this.currency.exponent()),
      };
    });
  });

  protected readonly bars = computed<Bar[]>(() => {
    const d = this.data();
    if (!d || d.daily.length === 0) {
      return [];
    }
    const max = this.scaleMax();
    const plotWidth = this.chartWidth() - AXIS_WIDTH;
    const band = plotWidth / d.daily.length;
    const width = Math.max(2, Math.min(24, band * 0.62));
    return d.daily.map((p, i) => {
      const slotX = AXIS_WIDTH + i * band;
      const cx = slotX + band / 2;
      const height = (p.netCents / max) * PLOT_HEIGHT;
      return {
        date: p.date,
        netCents: p.netCents,
        count: p.count,
        slotX,
        slotWidth: band,
        cx,
        top: PLOT_BOTTOM - height,
        path: columnPath(cx - width / 2, width, height, PLOT_BOTTOM),
      };
    });
  });

  protected readonly peak = computed(() => {
    const daily = this.data()?.daily ?? [];
    return daily.length === 0 || daily.every((p) => p.netCents === 0)
      ? null
      : daily.reduce((best, p) => (p.netCents > best.netCents ? p : best));
  });

  protected readonly labelEvery = computed(() => {
    const n = this.bars().length;
    const fits = Math.max(1, Math.floor((this.chartWidth() - AXIS_WIDTH) / 48));
    return Math.max(1, Math.ceil(n / fits));
  });

  protected readonly delta = computed(() => {
    const s = this.data()?.sales;
    if (!s || s.previousNetCents === 0) {
      return 0;
    }
    return ((s.netCents - s.previousNetCents) / s.previousNetCents) * 100;
  });
  protected readonly marginRate = computed(() => {
    const s = this.data()?.sales;
    return !s || s.netCents === 0 ? 0 : (s.marginCents / s.netCents) * 100;
  });

  constructor() {
    void this.load(29);
    if (inject(PermissionService).has('PROCUREMENT_VIEW')) {
      firstValueFrom(
        this.http.get<PurchaseOrderSummary>(`${this.env.apiUrl}/v1/procurement/orders/summary`),
      ).then(
        (summary) => this.purchaseOrders.set(summary),
        () => this.purchaseOrders.set(null),
      );
    }
    const destroyRef = inject(DestroyRef);
    let observer: ResizeObserver | null = null;
    effect(() => {
      const element = this.chart()?.nativeElement;
      observer?.disconnect();
      if (!element) {
        return;
      }
      observer = new ResizeObserver(([entry]) =>
        this.chartWidth.set(Math.max(320, Math.floor(entry.contentRect.width))),
      );
      observer.observe(element);
    });
    destroyRef.onDestroy(() => observer?.disconnect());
  }

  protected preset(days: number): void {
    this.from = isoDate(daysAgo(days));
    this.to = isoDate(new Date());
    void this.load(days);
  }

  protected label(method: string): string {
    return METHOD_LABELS[method] ?? method;
  }

  protected share(amountCents: number): number {
    const total = this.data()?.payments.reduce((sum, p) => sum + p.amountCents, 0) ?? 0;
    return total === 0 ? 0 : (amountCents / total) * 100;
  }

  protected async load(preset: number | null = null): Promise<void> {
    this.error.set(null);
    this.hovered.set(null);
    this.activePreset.set(preset);
    try {
      this.data.set(
        await firstValueFrom(
          this.http.get<Dashboard>(`${this.env.apiUrl}/v1/dashboard`, {
            params: { from: this.from, to: this.to },
          }),
        ),
      );
    } catch (error) {
      this.data.set(null);
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Tableau de bord indisponible',
      );
    }
  }
}

function daysAgo(days: number): Date {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date;
}

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}
