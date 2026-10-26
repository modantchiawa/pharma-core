import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { BalanceRow, BalanceSheet, IncomeStatement } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';

type Tab = 'result' | 'balance-sheet' | 'trial-balance';

/** Management accounting: income statement, simplified balance sheet, trial balance, export. */
@Component({
  selector: 'app-accounting-page',
  imports: [FormsModule, MoneyPipe, DatePipe, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Comptabilité</h1>
          <p class="page-subtitle">
            Compte de résultat, bilan et balance issus des écritures automatiques (SYSCOHADA)
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <a class="btn btn-secondary" [href]="exportUrl()" download>
            <app-icon name="download" [size]="16" />
            Exporter le journal (CSV)
          </a>
          <button
            type="button"
            class="btn btn-secondary"
            *appHasPermission="'ACCOUNTING_MANAGE'"
            (click)="closeMonth()"
          >
            <app-icon name="lock" [size]="16" />
            Clôturer le mois de {{ from | date: 'MMMM yyyy' }}
          </button>
        </div>
      </header>

      <div class="space-y-5">
        <div class="flex flex-wrap items-end justify-between gap-3">
          <div class="-mx-1 max-w-full overflow-x-auto px-1">
            <div class="segmented" role="tablist">
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="tab() === 'result'"
                (click)="open('result')"
              >
                Compte de résultat
              </button>
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="tab() === 'balance-sheet'"
                (click)="open('balance-sheet')"
              >
                Bilan
              </button>
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="tab() === 'trial-balance'"
                (click)="open('trial-balance')"
              >
                Balance
              </button>
            </div>
          </div>
          <div class="flex flex-wrap items-end gap-2">
            <label class="block">
              <span class="label">Du</span>
              <input type="date" class="input" [(ngModel)]="from" />
            </label>
            <label class="block">
              <span class="label">au</span>
              <input type="date" class="input" [(ngModel)]="to" />
            </label>
            <button type="button" class="btn btn-secondary" (click)="open(tab())">
              <app-icon name="refresh" [size]="16" />
              Actualiser
            </button>
          </div>
        </div>

        @if (status(); as text) {
          <div class="alert alert-info" role="status">
            <app-icon name="info" [size]="16" class="mt-0.5" />
            <span>{{ text }}</span>
          </div>
        }

        @switch (tab()) {
          @case ('result') {
            @if (result(); as r) {
              <div class="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
                <div class="grid gap-4 sm:grid-cols-3 lg:order-2 lg:grid-cols-1">
                  <div class="card kpi">
                    <div class="kpi-label">
                      <span class="kpi-icon"><app-icon name="receipt" [size]="16" /></span>
                      Ventes HT
                    </div>
                    <div class="kpi-value">{{ r.salesCents | money }}</div>
                    <div class="kpi-foot">Chiffre d'affaires de la période</div>
                  </div>
                  <div class="card kpi">
                    <div class="kpi-label">
                      <span class="kpi-icon"><app-icon name="trendUp" [size]="16" /></span>
                      Marge brute
                    </div>
                    <div class="kpi-value">{{ r.grossMarginCents | money }}</div>
                    <div class="kpi-foot">Ventes moins coût des marchandises</div>
                  </div>
                  <div class="card kpi">
                    <div class="kpi-label">
                      <span class="kpi-icon"><app-icon name="accounting" [size]="16" /></span>
                      Résultat de la période
                    </div>
                    <div
                      class="kpi-value"
                      [class.text-success]="r.resultCents >= 0"
                      [class.text-danger]="r.resultCents < 0"
                    >
                      {{ r.resultCents | money }}
                    </div>
                    <div class="kpi-foot">Après charges et autres produits</div>
                  </div>
                </div>

                <section class="card min-w-0">
                  <div class="card-header">
                    <h2 class="card-title">Compte de résultat</h2>
                    <span class="text-xs text-muted">
                      {{ from | date: 'dd/MM/yyyy' }} – {{ to | date: 'dd/MM/yyyy' }}
                    </span>
                  </div>
                  <table class="data-table statement">
                    <tbody>
                      <tr class="subtotal">
                        <td>Ventes HT</td>
                        <td class="num">{{ r.salesCents | money }}</td>
                      </tr>
                      <tr>
                        <td class="pl-8 text-muted">Achats HT</td>
                        <td class="num">{{ r.purchasesCents | money }}</td>
                      </tr>
                      <tr>
                        <td class="pl-8 text-muted">Stock initial</td>
                        <td class="num">{{ r.openingStockCents | money }}</td>
                      </tr>
                      <tr>
                        <td class="pl-8 text-muted">Stock final</td>
                        <td class="num">{{ r.closingStockCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Coût des marchandises vendues</td>
                        <td class="num">{{ r.costOfGoodsSoldCents | money }}</td>
                      </tr>
                      <tr class="subtotal">
                        <td>Marge brute</td>
                        <td class="num">{{ r.grossMarginCents | money }}</td>
                      </tr>
                      @for (entry of expenses(r); track entry[0]) {
                        <tr>
                          <td class="pl-8 text-muted">
                            Charge <span class="font-mono text-xs">{{ entry[0] }}</span>
                          </td>
                          <td class="num">{{ entry[1] | money }}</td>
                        </tr>
                      }
                      <tr>
                        <td>Autres charges</td>
                        <td class="num">{{ r.otherExpensesCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Autres produits</td>
                        <td class="num">{{ r.otherIncomeCents | money }}</td>
                      </tr>
                      <tr
                        class="total"
                        [class.text-success]="r.resultCents >= 0"
                        [class.text-danger]="r.resultCents < 0"
                      >
                        <td>Résultat de la période</td>
                        <td class="num">{{ r.resultCents | money }}</td>
                      </tr>
                    </tbody>
                  </table>
                </section>
              </div>
            }
          }
          @case ('balance-sheet') {
            @if (sheet(); as b) {
              <div class="grid gap-4 md:grid-cols-2">
                <section class="card">
                  <div class="card-header">
                    <h2 class="card-title">Actif</h2>
                    <span class="text-xs text-muted">au {{ b.asOf | date: 'dd/MM/yyyy' }}</span>
                  </div>
                  <table class="data-table statement">
                    <tbody>
                      <tr>
                        <td>Stock de marchandises (coût moyen)</td>
                        <td class="num">{{ b.stockCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Créances clients</td>
                        <td class="num">{{ b.customerReceivablesCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Créances tiers payant</td>
                        <td class="num">{{ b.thirdPartyReceivablesCents | money }}</td>
                      </tr>
                      <tr>
                        <td>TVA récupérable</td>
                        <td class="num">{{ b.vatDeductibleCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Caisse</td>
                        <td class="num">{{ b.cashCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Banque</td>
                        <td class="num">{{ b.bankCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Mobile money</td>
                        <td class="num">{{ b.mobileMoneyCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Chèques et cartes à encaisser</td>
                        <td class="num">{{ b.chequesCardsCents | money }}</td>
                      </tr>
                      <tr class="total">
                        <td>Total actif</td>
                        <td class="num">{{ b.totalAssetsCents | money }}</td>
                      </tr>
                    </tbody>
                  </table>
                </section>
                <section class="card self-start">
                  <div class="card-header">
                    <h2 class="card-title">Passif</h2>
                    <span class="text-xs text-muted">au {{ b.asOf | date: 'dd/MM/yyyy' }}</span>
                  </div>
                  <table class="data-table statement">
                    <tbody>
                      <tr>
                        <td>Capital</td>
                        <td class="num">{{ b.capitalCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Résultat cumulé</td>
                        <td class="num">{{ b.cumulativeResultCents | money }}</td>
                      </tr>
                      <tr>
                        <td>Dettes fournisseurs</td>
                        <td class="num">{{ b.suppliersCents | money }}</td>
                      </tr>
                      <tr>
                        <td>TVA à reverser</td>
                        <td class="num">{{ b.vatCollectedCents | money }}</td>
                      </tr>
                      <tr class="total">
                        <td>Total passif</td>
                        <td class="num">{{ b.totalLiabilitiesCents | money }}</td>
                      </tr>
                    </tbody>
                  </table>
                </section>
              </div>
              <p class="flex items-start gap-2 text-xs text-subtle">
                <app-icon name="info" [size]="14" class="mt-px" />
                Bilan de gestion établi à partir des écritures automatiques (SYSCOHADA). Les états
                de synthèse officiels sont validés par votre comptable à partir de l'export.
              </p>
            }
          }
          @case ('trial-balance') {
            <section class="card">
              <div class="card-header">
                <h2 class="card-title">Balance générale</h2>
                <span class="badge">{{ rows().length }} compte(s)</span>
              </div>
              <div class="overflow-x-auto">
                <table class="data-table">
                  <thead class="whitespace-nowrap">
                    <tr>
                      <th>Compte</th>
                      <th>Intitulé</th>
                      <th class="num text-right">Débit</th>
                      <th class="num text-right">Crédit</th>
                      <th class="num text-right">Solde</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of rows(); track row.account) {
                      <tr>
                        <td class="font-mono text-xs font-medium">{{ row.account }}</td>
                        <td class="min-w-48">{{ row.label }}</td>
                        <td class="num whitespace-nowrap">{{ row.debitCents | money }}</td>
                        <td class="num whitespace-nowrap">{{ row.creditCents | money }}</td>
                        <td class="num whitespace-nowrap font-semibold">
                          {{ row.balanceCents | money }}
                        </td>
                      </tr>
                    } @empty {
                      <tr>
                        <td colspan="5">
                          <div class="empty-state">
                            <app-icon name="accounting" [size]="22" />
                            <p>Aucune écriture sur la période.</p>
                          </div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .statement td.num {
      white-space: nowrap;
    }
    .statement tr.subtotal td {
      font-weight: 600;
      background: var(--surface-2);
    }
    .statement tr.total td {
      font-size: 1rem;
      font-weight: 700;
      border-top: 1px solid var(--border-strong);
    }
  `,
})
export class AccountingPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/accounting`;

  protected readonly tab = signal<Tab>('result');
  protected readonly result = signal<IncomeStatement | null>(null);
  protected readonly sheet = signal<BalanceSheet | null>(null);
  protected readonly rows = signal<BalanceRow[]>([]);
  protected readonly status = signal<string | null>(null);
  protected from = firstDayOfMonth();
  protected to = today();

  constructor() {
    void this.open('result');
  }

  protected expenses(r: IncomeStatement): [string, number][] {
    return Object.entries(r.expensesByAccount);
  }

  protected exportUrl(): string {
    return `${this.api}/journal/export?from=${this.from}&to=${this.to}`;
  }

  protected async open(tab: Tab): Promise<void> {
    this.tab.set(tab);
    const params = { from: this.from, to: this.to };
    try {
      if (tab === 'result') {
        this.result.set(
          await firstValueFrom(
            this.http.get<IncomeStatement>(`${this.api}/income-statement`, { params }),
          ),
        );
      } else if (tab === 'balance-sheet') {
        this.sheet.set(
          await firstValueFrom(
            this.http.get<BalanceSheet>(`${this.api}/balance-sheet`, { params: { asOf: this.to } }),
          ),
        );
      } else {
        this.rows.set(
          await firstValueFrom(
            this.http.get<BalanceRow[]>(`${this.api}/trial-balance`, { params }),
          ),
        );
      }
    } catch {
      this.status.set('Données comptables indisponibles');
    }
  }

  protected async closeMonth(): Promise<void> {
    const month = this.from.slice(0, 7);
    if (
      !confirm(
        `Clôturer définitivement le mois ${month} ? Aucune écriture ne pourra plus y être passée.`,
      )
    ) {
      return;
    }
    try {
      await firstValueFrom(this.http.post(`${this.api}/periods/${month}/closure`, {}));
      this.status.set(`Mois ${month} clôturé`);
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Clôture refusée',
      );
    }
  }
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function firstDayOfMonth(): string {
  return today().slice(0, 8) + '01';
}
