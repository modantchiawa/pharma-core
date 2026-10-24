import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { CashSessionReport, PaymentMethod } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { TerminalService } from '../../core/offline/terminal.service';
import { CurrencyService } from '../../core/ui/currency.service';
import { MoneyPipe } from '../../shared/money.pipe';

const METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Espèces',
  CARD: 'Carte',
  CHECK: 'Chèque',
  THIRD_PARTY_PAYER: 'Tiers payant',
  VOUCHER: 'Avoir',
  MOBILE_MONEY: 'Mobile money',
  CREDIT: 'Crédit client',
};

const EXPENSE_ACCOUNTS = [
  { code: '6052', label: 'Électricité et eau' },
  { code: '6181', label: 'Transport et livraison' },
  { code: '622', label: 'Locations' },
  { code: '6281', label: 'Téléphone et Internet' },
  { code: '631', label: 'Frais bancaires / mobile money' },
  { code: '661', label: 'Personnel' },
  { code: '658', label: 'Autres charges' },
];

/** Opening with a float, X report at any time, closing with a count: the Z report. */
@Component({
  selector: 'app-cash-session-page',
  imports: [FormsModule, DatePipe, MoneyPipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-4 p-4 lg:grid-cols-[1fr_360px]">
      <section class="space-y-4">
        @if (session(); as s) {
          <div class="rounded-lg border bg-white p-4">
            <h2 class="text-lg font-semibold">
              Session {{ s.status === 'OPEN' ? 'en cours' : 'clôturée' }} · poste {{ s.terminalId }}
            </h2>
            <p class="text-sm text-slate-600">
              Ouverte le {{ s.openedAt | date: 'dd/MM HH:mm' }} avec un fond de
              {{ s.openingFloatCents | money }}
              @if (s.closedAt) {
                · clôturée le {{ s.closedAt | date: 'dd/MM HH:mm' }}
              }
            </p>
            <table class="mt-3 w-full text-sm">
              <tbody>
                <tr>
                  <td class="py-1">
                    Ventes ({{ s.salesCount }}, {{ s.cancelledCount }} annulée(s))
                  </td>
                  <td class="text-right font-semibold">{{ s.salesNetCents | money }}</td>
                </tr>
                @for (entry of methods(s); track entry[0]) {
                  <tr>
                    <td class="py-1 pl-4">{{ label(entry[0]) }}</td>
                    <td class="text-right">{{ entry[1] | money }}</td>
                  </tr>
                }
                @for (entry of providers(s); track entry[0]) {
                  <tr>
                    <td class="py-1 pl-8 text-slate-500">{{ entry[0] }}</td>
                    <td class="text-right text-slate-500">{{ entry[1] | money }}</td>
                  </tr>
                }
                <tr>
                  <td class="py-1">Encaissements de créances (espèces)</td>
                  <td class="text-right">{{ s.customerPaymentsCashCents | money }}</td>
                </tr>
                <tr>
                  <td class="py-1">Apports en caisse</td>
                  <td class="text-right">{{ s.cashInCents | money }}</td>
                </tr>
                <tr>
                  <td class="py-1">Sorties (banque, remboursements)</td>
                  <td class="text-right">-{{ s.cashOutCents | money }}</td>
                </tr>
                <tr>
                  <td class="py-1">Dépenses</td>
                  <td class="text-right">-{{ s.expensesCents | money }}</td>
                </tr>
                <tr class="border-t font-bold">
                  <td class="py-1">Espèces attendues en caisse</td>
                  <td class="text-right">{{ s.expectedCashCents | money }}</td>
                </tr>
                @if (s.countedCashCents !== null) {
                  <tr>
                    <td class="py-1">Espèces comptées</td>
                    <td class="text-right">{{ s.countedCashCents | money }}</td>
                  </tr>
                  <tr
                    class="font-bold"
                    [class.text-red-700]="(s.differenceCents ?? 0) < 0"
                    [class.text-green-700]="(s.differenceCents ?? 0) > 0"
                  >
                    <td class="py-1">Écart</td>
                    <td class="text-right">{{ s.differenceCents | money }}</td>
                  </tr>
                }
              </tbody>
            </table>
            @if (s.movements.length > 0) {
              <h3 class="mt-3 font-semibold">Mouvements</h3>
              <ul class="text-sm">
                @for (m of s.movements; track m.id) {
                  <li class="flex justify-between border-t py-1">
                    <span>{{ m.occurredAt | date: 'HH:mm' }} · {{ m.kind }} · {{ m.reason }}</span
                    ><span>{{ m.amountCents | money }}</span>
                  </li>
                }
              </ul>
            }
            <button type="button" class="mt-3 rounded border px-3 py-1" (click)="print()">
              Imprimer le rapport {{ s.status === 'OPEN' ? 'X' : 'Z' }}
            </button>
          </div>
        } @else {
          <p class="rounded-lg border bg-white p-4 text-slate-600">
            Aucune session ouverte sur le poste {{ terminal.terminalId }}.
          </p>
        }

        <div class="rounded-lg border bg-white p-4">
          <h3 class="mb-2 font-semibold">Sessions récentes</h3>
          <table class="w-full text-sm">
            <thead class="text-left text-slate-500">
              <tr>
                <th>Poste</th>
                <th>Ouverture</th>
                <th>Clôture</th>
                <th class="text-right">Ventes</th>
                <th class="text-right">Écart</th>
              </tr>
            </thead>
            <tbody>
              @for (r of recent(); track r.sessionId) {
                <tr class="cursor-pointer border-t hover:bg-slate-50" (click)="session.set(r)">
                  <td>{{ r.terminalId }}</td>
                  <td>{{ r.openedAt | date: 'dd/MM HH:mm' }}</td>
                  <td>{{ r.closedAt ? (r.closedAt | date: 'dd/MM HH:mm') : 'ouverte' }}</td>
                  <td class="text-right">{{ r.salesNetCents | money }}</td>
                  <td class="text-right">{{ r.differenceCents | money }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <aside class="space-y-4" *appHasPermission="'CASH_SESSION_MANAGE'">
        @if (!session() || session()?.status === 'CLOSED') {
          <form class="space-y-2 rounded-lg border bg-white p-4" (ngSubmit)="open()">
            <h3 class="font-semibold">Ouvrir la caisse</h3>
            <label class="block text-sm"
              >Fond de caisse<input
                inputmode="decimal"
                class="field"
                [(ngModel)]="floatAmount"
                name="float"
            /></label>
            <button type="submit" class="w-full rounded bg-brand py-2 text-on-brand">Ouvrir</button>
          </form>
        } @else {
          <form
            class="space-y-2 rounded-lg border bg-white p-4"
            (ngSubmit)="movement()"
            *appHasPermission="'CASH_EXPENSE'"
          >
            <h3 class="font-semibold">Mouvement de caisse</h3>
            <select class="field" [(ngModel)]="kind" name="kind">
              <option value="EXPENSE">Dépense</option>
              <option value="CASH_OUT">Remise en banque</option>
              <option value="CASH_IN">Apport en caisse</option>
            </select>
            @if (kind === 'EXPENSE') {
              <select class="field" [(ngModel)]="expenseAccount" name="account">
                @for (a of expenseAccounts; track a.code) {
                  <option [value]="a.code">{{ a.code }} {{ a.label }}</option>
                }
              </select>
            }
            <input
              inputmode="decimal"
              class="field"
              placeholder="Montant"
              [(ngModel)]="movementAmount"
              name="amount"
              required
            />
            <input
              class="field"
              placeholder="Motif"
              [(ngModel)]="reason"
              name="reason"
              required
              maxlength="255"
            />
            <button type="submit" class="w-full rounded border border-brand py-2 text-brand">
              Enregistrer
            </button>
          </form>
          <form class="space-y-2 rounded-lg border bg-white p-4" (ngSubmit)="close()">
            <h3 class="font-semibold">Clôturer la caisse (rapport Z)</h3>
            <label class="block text-sm"
              >Espèces comptées<input
                inputmode="decimal"
                class="field"
                [(ngModel)]="counted"
                name="counted"
                required
            /></label>
            <textarea
              class="field"
              rows="2"
              placeholder="Observations"
              [(ngModel)]="notes"
              name="notes"
            ></textarea>
            <button type="submit" class="w-full rounded bg-brand py-2 text-on-brand">
              Clôturer
            </button>
          </form>
        }
        @if (error(); as text) {
          <p class="text-sm text-red-700" role="alert">{{ text }}</p>
        }
      </aside>
    </div>
  `,
  styles: `
    .field {
      display: block;
      width: 100%;
      margin-top: 0.25rem;
      border: 1px solid #cbd5e1;
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
  `,
})
export class CashSessionPage {
  protected readonly terminal = inject(TerminalService);
  private readonly http = inject(HttpClient);
  private readonly currency = inject(CurrencyService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/cash/sessions`;

  protected readonly session = signal<CashSessionReport | null>(null);
  protected readonly recent = signal<CashSessionReport[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly expenseAccounts = EXPENSE_ACCOUNTS;
  protected floatAmount = '';
  protected counted = '';
  protected notes = '';
  protected kind: 'EXPENSE' | 'CASH_OUT' | 'CASH_IN' = 'EXPENSE';
  protected expenseAccount = EXPENSE_ACCOUNTS[0].code;
  protected movementAmount = '';
  protected reason = '';

  constructor() {
    void this.load();
  }

  protected label(method: string): string {
    return METHOD_LABELS[method as PaymentMethod] ?? method;
  }

  protected methods(s: CashSessionReport): [string, number][] {
    return Object.entries(s.byPaymentMethod) as [string, number][];
  }

  protected providers(s: CashSessionReport): [string, number][] {
    return Object.entries(s.mobileMoneyByProvider);
  }

  protected print(): void {
    window.print();
  }

  protected async open(): Promise<void> {
    await this.run(() =>
      firstValueFrom(
        this.http.post<CashSessionReport>(this.api, {
          terminalId: this.terminal.terminalId,
          openingFloatCents: this.currency.parse(this.floatAmount),
        }),
      ),
    );
  }

  protected async close(): Promise<void> {
    const current = this.session();
    if (!current) {
      return;
    }
    await this.run(() =>
      firstValueFrom(
        this.http.post<CashSessionReport>(`${this.api}/${current.sessionId}/closure`, {
          countedCashCents: this.currency.parse(this.counted),
          notes: this.notes || null,
        }),
      ),
    );
  }

  protected async movement(): Promise<void> {
    const current = this.session();
    if (!current) {
      return;
    }
    await this.run(async () => {
      await firstValueFrom(
        this.http.post(`${this.api}/${current.sessionId}/movements`, {
          kind: this.kind,
          amountCents: this.currency.parse(this.movementAmount),
          reason: this.reason,
          expenseAccount: this.kind === 'EXPENSE' ? this.expenseAccount : null,
        }),
      );
      this.movementAmount = '';
      this.reason = '';
      return firstValueFrom(this.http.get<CashSessionReport>(`${this.api}/${current.sessionId}`));
    });
  }

  private async run(action: () => Promise<CashSessionReport>): Promise<void> {
    this.error.set(null);
    try {
      this.session.set(await action());
      await this.loadRecent();
    } catch (error) {
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Opération refusée',
      );
    }
  }

  private async load(): Promise<void> {
    try {
      const response = await firstValueFrom(
        this.http.get<CashSessionReport>(`${this.api}/open`, {
          params: { terminalId: this.terminal.terminalId },
          observe: 'response',
        }),
      );
      this.session.set(response.status === 200 ? response.body : null);
    } catch {
      this.session.set(null);
    }
    await this.loadRecent();
  }

  private async loadRecent(): Promise<void> {
    try {
      this.recent.set(
        await firstValueFrom(
          this.http.get<CashSessionReport[]>(this.api, { params: { limit: 15 } }),
        ),
      );
    } catch {
      this.recent.set([]);
    }
  }
}
