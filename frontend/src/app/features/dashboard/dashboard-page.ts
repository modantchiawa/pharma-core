import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Dashboard } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { MoneyPipe } from '../../shared/money.pipe';

interface Bar {
  date: string;
  netCents: number;
  count: number;
  x: number;
  height: number;
}

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
  imports: [FormsModule, MoneyPipe, DatePipe, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <form class="flex flex-wrap items-end gap-2" (ngSubmit)="load()">
        <label class="text-sm"
          >Du<input type="date" class="field" [(ngModel)]="from" name="from" required
        /></label>
        <label class="text-sm"
          >Au<input type="date" class="field" [(ngModel)]="to" name="to" required
        /></label>
        <button type="submit" class="rounded bg-brand px-3 py-2 text-on-brand">Afficher</button>
        <button type="button" class="rounded border px-3 py-2" (click)="preset(0)">
          Aujourd'hui
        </button>
        <button type="button" class="rounded border px-3 py-2" (click)="preset(6)">7 jours</button>
        <button type="button" class="rounded border px-3 py-2" (click)="preset(29)">
          30 jours
        </button>
      </form>

      @if (data(); as d) {
        <section class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4" aria-label="Indicateurs">
          <div class="tile">
            <p class="label">Chiffre d'affaires</p>
            <p class="value">{{ d.sales.netCents | money }}</p>
            <p
              class="delta"
              [class.text-green-700]="delta() >= 0"
              [class.text-red-700]="delta() < 0"
            >
              {{ delta() >= 0 ? '+' : '' }}{{ delta() | number: '1.0-0' }} % vs période précédente
            </p>
          </div>
          <div class="tile">
            <p class="label">Marge brute</p>
            <p class="value">{{ d.sales.marginCents | money }}</p>
            <p class="delta">{{ marginRate() | number: '1.0-1' }} % du CA</p>
          </div>
          <div class="tile">
            <p class="label">Ventes</p>
            <p class="value">{{ d.sales.count }}</p>
            <p class="delta">
              panier moyen {{ d.sales.averageBasketCents | money }} · {{ d.sales.cancelledCount }}
              annulée(s)
            </p>
          </div>
          <div class="tile">
            <p class="label">Créances clients</p>
            <p class="value">{{ d.receivables.customerCreditCents | money }}</p>
            <p class="delta">{{ d.receivables.debtors }} débiteur(s)</p>
          </div>
          <div class="tile">
            <p class="label">Valeur du stock (coût)</p>
            <p class="value">{{ d.stock.valueAtCostCents | money }}</p>
            <p class="delta">{{ d.stock.belowThreshold }} produit(s) sous le seuil</p>
          </div>
          <div class="tile" [class.alert]="d.stock.expiringLots > 0">
            <p class="label">Périme sous 90 jours</p>
            <p class="value">{{ d.stock.expiringWithin90DaysCents | money }}</p>
            <p class="delta">
              {{ d.stock.expiringLots }} lot(s) · {{ d.stock.shortfallProducts }} rupture(s)
            </p>
          </div>
          <div class="tile" [class.alert]="d.cash.differenceCents !== 0">
            <p class="label">Écarts de caisse</p>
            <p class="value">{{ d.cash.differenceCents | money }}</p>
            <p class="delta">{{ d.cash.sessionsClosed }} session(s) clôturée(s)</p>
          </div>
          <div class="tile">
            <p class="label">Dépenses de caisse</p>
            <p class="value">{{ d.cash.expensesCents | money }}</p>
            <p class="delta">sur la période</p>
          </div>
        </section>

        <section class="rounded-lg border bg-white p-4">
          <h2 class="mb-2 font-semibold">Chiffre d'affaires par jour</h2>
          @if (bars().length > 0) {
            <svg
              [attr.viewBox]="'0 0 ' + chartWidth + ' 220'"
              class="w-full"
              role="img"
              aria-label="Chiffre d'affaires par jour"
            >
              <line x1="0" y1="190" [attr.x2]="chartWidth" y2="190" stroke="#94a3b8" />
              @for (bar of bars(); track bar.date) {
                <g>
                  <rect
                    [attr.x]="bar.x"
                    [attr.y]="190 - bar.height"
                    [attr.width]="barWidth()"
                    [attr.height]="bar.height"
                    class="fill-brand"
                  >
                    <title>
                      {{ bar.date | date: 'dd/MM' }} : {{ bar.netCents | money }} ({{ bar.count }}
                      ventes)
                    </title>
                  </rect>
                  @if (bars().length <= 31) {
                    <text
                      [attr.x]="bar.x + barWidth() / 2"
                      y="206"
                      text-anchor="middle"
                      font-size="10"
                      fill="#475569"
                    >
                      {{ bar.date | date: 'dd/MM' }}
                    </text>
                  }
                </g>
              }
            </svg>
          } @else {
            <p class="text-sm text-slate-500">Aucune vente sur la période.</p>
          }
        </section>

        <div class="grid gap-4 lg:grid-cols-2">
          <section class="rounded-lg border bg-white p-4">
            <h2 class="mb-2 font-semibold">Encaissements par mode</h2>
            <table class="w-full text-sm">
              <tbody>
                @for (p of d.payments; track p.method + (p.provider ?? '')) {
                  <tr class="border-t">
                    <td class="py-1">
                      {{ label(p.method) }} {{ p.provider ? '· ' + p.provider : '' }}
                    </td>
                    <td class="py-1 text-right">{{ p.amountCents | money }}</td>
                    <td class="py-1 text-right text-slate-500">
                      {{ share(p.amountCents) | number: '1.0-0' }} %
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td class="py-2 text-slate-500">Aucun encaissement</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>
          <section class="rounded-lg border bg-white p-4">
            <h2 class="mb-2 font-semibold">Meilleures ventes</h2>
            <table class="w-full text-sm">
              <thead class="text-left text-slate-500">
                <tr>
                  <th>Produit</th>
                  <th class="text-right">Qté</th>
                  <th class="text-right">CA</th>
                </tr>
              </thead>
              <tbody>
                @for (t of d.topProducts; track t.productId) {
                  <tr class="border-t">
                    <td class="py-1">{{ t.designation }}</td>
                    <td class="py-1 text-right">{{ t.quantity }}</td>
                    <td class="py-1 text-right">{{ t.netCents | money }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td class="py-2 text-slate-500" colspan="3">Aucune vente</td>
                  </tr>
                }
              </tbody>
            </table>
          </section>
        </div>
      } @else if (error(); as text) {
        <p class="rounded bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">{{ text }}</p>
      }
    </div>
  `,
  styles: `
    .field {
      display: block;
      border: 1px solid #cbd5e1;
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
    .tile {
      border: 1px solid #e2e8f0;
      border-radius: 0.5rem;
      background: white;
      padding: 0.75rem 1rem;
    }
    .tile.alert {
      border-color: #f59e0b;
    }
    .label {
      font-size: 0.75rem;
      text-transform: uppercase;
      color: #64748b;
    }
    .value {
      font-size: 1.5rem;
      font-weight: 700;
    }
    .delta {
      font-size: 0.75rem;
      color: #64748b;
    }
  `,
})
export class DashboardPage {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  protected readonly currency = inject(CurrencyService);
  protected readonly data = signal<Dashboard | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly chartWidth = 760;
  protected from = isoDate(daysAgo(29));
  protected to = isoDate(new Date());

  protected readonly bars = computed<Bar[]>(() => {
    const d = this.data();
    if (!d || d.daily.length === 0) {
      return [];
    }
    const peak = Math.max(...d.daily.map((p) => p.netCents), 1);
    const band = this.chartWidth / d.daily.length;
    return d.daily.map((p, i) => ({
      date: p.date,
      netCents: p.netCents,
      count: p.count,
      x: i * band + band * 0.15,
      height: Math.round((p.netCents / peak) * 170),
    }));
  });
  protected readonly barWidth = computed(() => {
    const n = this.data()?.daily.length ?? 1;
    return (this.chartWidth / Math.max(n, 1)) * 0.7;
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
    void this.load();
  }

  protected preset(days: number): void {
    this.from = isoDate(daysAgo(days));
    this.to = isoDate(new Date());
    void this.load();
  }

  protected label(method: string): string {
    return METHOD_LABELS[method] ?? method;
  }

  protected share(amountCents: number): number {
    const total = this.data()?.payments.reduce((sum, p) => sum + p.amountCents, 0) ?? 0;
    return total === 0 ? 0 : (amountCents / total) * 100;
  }

  protected async load(): Promise<void> {
    this.error.set(null);
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
