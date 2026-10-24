import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { BalanceRow, BalanceSheet, IncomeStatement } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';

type Tab = 'result' | 'balance-sheet' | 'trial-balance';

/** Management accounting: income statement, simplified balance sheet, trial balance, export. */
@Component({
  selector: 'app-accounting-page',
  imports: [FormsModule, MoneyPipe, DatePipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <div class="flex flex-wrap items-center gap-2">
        <nav class="flex gap-2" role="tablist">
          <button
            type="button"
            class="rounded px-3 py-1"
            [class.bg-brand]="tab() === 'result'"
            [class.text-on-brand]="tab() === 'result'"
            (click)="open('result')"
          >
            Compte de résultat
          </button>
          <button
            type="button"
            class="rounded px-3 py-1"
            [class.bg-brand]="tab() === 'balance-sheet'"
            [class.text-on-brand]="tab() === 'balance-sheet'"
            (click)="open('balance-sheet')"
          >
            Bilan
          </button>
          <button
            type="button"
            class="rounded px-3 py-1"
            [class.bg-brand]="tab() === 'trial-balance'"
            [class.text-on-brand]="tab() === 'trial-balance'"
            (click)="open('trial-balance')"
          >
            Balance
          </button>
        </nav>
        <label class="text-sm"
          >Du <input type="date" class="rounded border px-2 py-1" [(ngModel)]="from"
        /></label>
        <label class="text-sm"
          >au <input type="date" class="rounded border px-2 py-1" [(ngModel)]="to"
        /></label>
        <button type="button" class="rounded border px-3 py-1" (click)="open(tab())">
          Actualiser
        </button>
        <a class="ml-auto rounded border px-3 py-1" [href]="exportUrl()" download
          >Exporter le journal (CSV)</a
        >
        <button
          type="button"
          class="rounded border px-3 py-1"
          *appHasPermission="'ACCOUNTING_MANAGE'"
          (click)="closeMonth()"
        >
          Clôturer le mois de {{ from | date: 'MMMM yyyy' }}
        </button>
      </div>

      @switch (tab()) {
        @case ('result') {
          @if (result(); as r) {
            <table class="w-full max-w-2xl rounded border bg-white text-sm">
              <tbody>
                <tr class="border-b">
                  <td class="p-2">Ventes HT</td>
                  <td class="p-2 text-right font-semibold">{{ r.salesCents | money }}</td>
                </tr>
                <tr>
                  <td class="p-2 pl-6">Achats HT</td>
                  <td class="p-2 text-right">{{ r.purchasesCents | money }}</td>
                </tr>
                <tr>
                  <td class="p-2 pl-6">Stock initial</td>
                  <td class="p-2 text-right">{{ r.openingStockCents | money }}</td>
                </tr>
                <tr>
                  <td class="p-2 pl-6">Stock final</td>
                  <td class="p-2 text-right">{{ r.closingStockCents | money }}</td>
                </tr>
                <tr class="border-b">
                  <td class="p-2">Coût des marchandises vendues</td>
                  <td class="p-2 text-right">{{ r.costOfGoodsSoldCents | money }}</td>
                </tr>
                <tr class="border-b font-semibold">
                  <td class="p-2">Marge brute</td>
                  <td class="p-2 text-right">{{ r.grossMarginCents | money }}</td>
                </tr>
                @for (entry of expenses(r); track entry[0]) {
                  <tr>
                    <td class="p-2 pl-6">Charge {{ entry[0] }}</td>
                    <td class="p-2 text-right">{{ entry[1] | money }}</td>
                  </tr>
                }
                <tr>
                  <td class="p-2">Autres charges</td>
                  <td class="p-2 text-right">{{ r.otherExpensesCents | money }}</td>
                </tr>
                <tr class="border-b">
                  <td class="p-2">Autres produits</td>
                  <td class="p-2 text-right">{{ r.otherIncomeCents | money }}</td>
                </tr>
                <tr
                  class="text-lg font-bold"
                  [class.text-green-700]="r.resultCents >= 0"
                  [class.text-red-700]="r.resultCents < 0"
                >
                  <td class="p-2">Résultat de la période</td>
                  <td class="p-2 text-right">{{ r.resultCents | money }}</td>
                </tr>
              </tbody>
            </table>
          }
        }
        @case ('balance-sheet') {
          @if (sheet(); as b) {
            <div class="grid max-w-4xl gap-4 md:grid-cols-2">
              <table class="rounded border bg-white text-sm">
                <thead class="bg-slate-100">
                  <tr>
                    <th class="p-2 text-left" colspan="2">
                      ACTIF au {{ b.asOf | date: 'dd/MM/yyyy' }}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td class="p-2">Stock de marchandises (coût moyen)</td>
                    <td class="p-2 text-right">{{ b.stockCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Créances clients</td>
                    <td class="p-2 text-right">{{ b.customerReceivablesCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Créances tiers payant</td>
                    <td class="p-2 text-right">{{ b.thirdPartyReceivablesCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">TVA récupérable</td>
                    <td class="p-2 text-right">{{ b.vatDeductibleCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Caisse</td>
                    <td class="p-2 text-right">{{ b.cashCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Banque</td>
                    <td class="p-2 text-right">{{ b.bankCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Mobile money</td>
                    <td class="p-2 text-right">{{ b.mobileMoneyCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Chèques et cartes à encaisser</td>
                    <td class="p-2 text-right">{{ b.chequesCardsCents | money }}</td>
                  </tr>
                  <tr class="border-t font-bold">
                    <td class="p-2">Total actif</td>
                    <td class="p-2 text-right">{{ b.totalAssetsCents | money }}</td>
                  </tr>
                </tbody>
              </table>
              <table class="rounded border bg-white text-sm">
                <thead class="bg-slate-100">
                  <tr>
                    <th class="p-2 text-left" colspan="2">PASSIF</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td class="p-2">Capital</td>
                    <td class="p-2 text-right">{{ b.capitalCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Résultat cumulé</td>
                    <td class="p-2 text-right">{{ b.cumulativeResultCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">Dettes fournisseurs</td>
                    <td class="p-2 text-right">{{ b.suppliersCents | money }}</td>
                  </tr>
                  <tr>
                    <td class="p-2">TVA à reverser</td>
                    <td class="p-2 text-right">{{ b.vatCollectedCents | money }}</td>
                  </tr>
                  <tr class="border-t font-bold">
                    <td class="p-2">Total passif</td>
                    <td class="p-2 text-right">{{ b.totalLiabilitiesCents | money }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="text-xs text-slate-500">
              Bilan de gestion établi à partir des écritures automatiques (SYSCOHADA). Les états de
              synthèse officiels sont validés par votre comptable à partir de l'export.
            </p>
          }
        }
        @case ('trial-balance') {
          <table class="w-full max-w-4xl rounded border bg-white text-sm">
            <thead class="bg-slate-100 text-left">
              <tr>
                <th class="p-2">Compte</th>
                <th class="p-2">Intitulé</th>
                <th class="p-2 text-right">Débit</th>
                <th class="p-2 text-right">Crédit</th>
                <th class="p-2 text-right">Solde</th>
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.account) {
                <tr class="border-t">
                  <td class="p-2 font-mono">{{ row.account }}</td>
                  <td class="p-2">{{ row.label }}</td>
                  <td class="p-2 text-right">{{ row.debitCents | money }}</td>
                  <td class="p-2 text-right">{{ row.creditCents | money }}</td>
                  <td class="p-2 text-right font-semibold">{{ row.balanceCents | money }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
      @if (status(); as text) {
        <p class="text-sm" role="status">{{ text }}</p>
      }
    </div>
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
