import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  CashPolicy,
  CashSessionReport,
  MOBILE_MONEY_PROVIDERS,
  PaymentMethod,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { PermissionService } from '../../core/auth/permission.service';
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

/** XOF notes and coins; the drawer total is derived from the count, never typed. */
const XOF_DENOMINATIONS = [10000, 5000, 2000, 1000, 500, 250, 200, 100, 50, 25, 10, 5];

interface SessionSale {
  id: string;
  ticketNumber: string;
  status: 'COMPLETED' | 'CANCELLED';
  occurredAt: string;
  totalNetCents: number;
}

/**
 * Opening with a float, X report at any time, blind closing: the cashier counts notes and coins
 * and declares electronic totals; expected figures appear only to approvers. Cancellations and
 * large differences are signed by a supervisor.
 */
@Component({
  selector: 'app-cash-session-page',
  imports: [FormsModule, DatePipe, MoneyPipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-4 p-4 lg:grid-cols-[1fr_380px]">
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
            @if (s.approvalStatus === 'PENDING') {
              <p class="mt-2 rounded bg-amber-50 px-3 py-2 text-sm text-amber-900" role="status">
                Écart au-dessus de la tolérance : en attente de l'approbation d'un superviseur.
                <button
                  *appHasPermission="'CASH_APPROVE'"
                  type="button"
                  class="ml-2 rounded bg-brand px-2 py-0.5 text-on-brand"
                  (click)="approve(s)"
                >
                  Approuver
                </button>
              </p>
            } @else if (s.approvalStatus === 'APPROVED') {
              <p class="mt-2 text-sm text-green-800">
                Clôture approuvée le {{ s.approvedAt | date: 'dd/MM HH:mm' }}
                @if (s.approvalNote) {
                  · {{ s.approvalNote }}
                }
              </p>
            }
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
                @if (s.expectedCashCents !== undefined) {
                  <tr class="border-t font-bold">
                    <td class="py-1">Espèces attendues en caisse</td>
                    <td class="text-right">{{ s.expectedCashCents | money }}</td>
                  </tr>
                } @else {
                  <tr class="border-t text-slate-500">
                    <td class="py-1" colspan="2">
                      Espèces attendues masquées : comptez la caisse à l'aveugle.
                    </td>
                  </tr>
                }
                @if (s.countedCashCents !== undefined && s.countedCashCents !== null) {
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
                    <td class="text-right">{{ s.differenceCents ?? 0 | money }}</td>
                  </tr>
                }
              </tbody>
            </table>
            @if (s.reconciliation.length > 0) {
              <h3 class="mt-3 font-semibold">Rapprochement par mode de paiement</h3>
              <table class="w-full text-sm">
                <thead class="text-left text-slate-500">
                  <tr>
                    <th>Mode</th>
                    <th class="text-right">Attendu</th>
                    <th class="text-right">Déclaré</th>
                    <th class="text-right">Écart</th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of s.reconciliation; track r.method + (r.provider ?? '')) {
                    <tr class="border-t" [class.text-red-700]="r.differenceCents !== 0">
                      <td>{{ label(r.method) }}{{ r.provider ? ' · ' + r.provider : '' }}</td>
                      <td class="text-right">{{ r.expectedCents | money }}</td>
                      <td class="text-right">{{ r.declaredCents | money }}</td>
                      <td class="text-right">{{ r.differenceCents | money }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
            @if (s.countedBreakdown.length > 0) {
              <p class="mt-2 text-xs text-slate-500">
                Coupures :
                @for (d of s.countedBreakdown; track d.valueCents) {
                  <span class="mr-2">{{ d.count }} × {{ d.valueCents | money }}</span>
                }
              </p>
            }
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
            @if (s.reportHash) {
              <p class="mt-2 break-all font-mono text-xs text-slate-400">
                Empreinte Z {{ s.reportHash }}<br />précédente {{ s.previousReportHash }}
              </p>
            }
            <button type="button" class="mt-3 rounded border px-3 py-1" (click)="print()">
              Imprimer le rapport {{ s.status === 'OPEN' ? 'X' : 'Z' }}
            </button>
          </div>

          <div class="rounded-lg border bg-white p-4">
            <h3 class="mb-2 font-semibold">Ventes de la session</h3>
            <table class="w-full text-sm">
              <tbody>
                @for (sale of sales(); track sale.id) {
                  <tr class="border-t" [class.text-slate-400]="sale.status === 'CANCELLED'">
                    <td class="py-1">{{ sale.occurredAt | date: 'HH:mm' }}</td>
                    <td class="py-1">{{ sale.ticketNumber }}</td>
                    <td class="py-1 text-right">{{ sale.totalNetCents | money }}</td>
                    <td class="py-1 text-right">
                      @if (sale.status === 'COMPLETED') {
                        <button
                          *appHasPermission="'SALE_CANCEL'"
                          type="button"
                          class="rounded border px-2 py-0.5"
                          (click)="startCancel(sale)"
                        >
                          Annuler
                        </button>
                      } @else {
                        annulée
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td class="py-2 text-slate-500">Aucune vente</td>
                  </tr>
                }
              </tbody>
            </table>
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
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (r of recent(); track r.sessionId) {
                <tr class="cursor-pointer border-t hover:bg-slate-50" (click)="select(r)">
                  <td>{{ r.terminalId }}</td>
                  <td>{{ r.openedAt | date: 'dd/MM HH:mm' }}</td>
                  <td>{{ r.closedAt ? (r.closedAt | date: 'dd/MM HH:mm') : 'ouverte' }}</td>
                  <td class="text-right">{{ r.salesNetCents | money }}</td>
                  <td class="text-right">{{ r.differenceCents ?? 0 | money }}</td>
                  <td>
                    @if (r.approvalStatus === 'PENDING') {
                      <span class="rounded bg-amber-100 px-1 text-xs text-amber-900"
                        >à approuver</span
                      >
                    }
                  </td>
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
            <p class="text-xs text-slate-500">Comptez les coupures ; le total se calcule seul.</p>
            <div class="grid grid-cols-3 gap-1 text-sm">
              @for (value of denominationValues; track value) {
                <label class="flex items-center gap-1">
                  <span class="w-14 text-right">{{ value | money }}</span>
                  <input
                    type="number"
                    min="0"
                    class="w-full rounded border px-1 py-0.5"
                    [ngModel]="counts[value] ?? null"
                    (ngModelChange)="setCount(value, $event)"
                    [name]="'d' + value"
                  />
                </label>
              }
            </div>
            <p class="text-right font-semibold">Espèces comptées : {{ countedTotal() | money }}</p>
            <h4 class="pt-1 text-sm font-semibold">Montants déclarés (relevés)</h4>
            @for (p of mobileProviders; track p) {
              <label class="flex items-center gap-2 text-sm"
                ><span class="w-32">{{ p }}</span
                ><input
                  inputmode="decimal"
                  class="field !mt-0"
                  [(ngModel)]="declared[p]"
                  [name]="'mm' + p"
                  placeholder="0"
              /></label>
            }
            <label class="flex items-center gap-2 text-sm"
              ><span class="w-32">Carte</span
              ><input
                inputmode="decimal"
                class="field !mt-0"
                [(ngModel)]="declared['CARD']"
                name="card"
                placeholder="0"
            /></label>
            <label class="flex items-center gap-2 text-sm"
              ><span class="w-32">Chèques</span
              ><input
                inputmode="decimal"
                class="field !mt-0"
                [(ngModel)]="declared['CHECK']"
                name="check"
                placeholder="0"
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
        <form
          class="space-y-2 rounded-lg border bg-white p-4"
          (ngSubmit)="savePolicy()"
          *appHasPermission="'CASH_APPROVE'"
        >
          <h3 class="font-semibold">Seuils d'approbation</h3>
          <label class="block text-sm"
            >Tolérance d'écart de caisse (0 = aucune approbation)<input
              inputmode="decimal"
              class="field"
              [(ngModel)]="policyDifference"
              name="policyDifference"
          /></label>
          <label class="block text-sm"
            >Annulation à approuver au-dessus de<input
              inputmode="decimal"
              class="field"
              [(ngModel)]="policyCancel"
              name="policyCancel"
          /></label>
          <button type="submit" class="w-full rounded border border-brand py-2 text-brand">
            Enregistrer les seuils
          </button>
        </form>
        @if (error(); as text) {
          <p class="text-sm text-red-700" role="alert">{{ text }}</p>
        }
      </aside>

      @if (cancelling(); as sale) {
        <div
          class="fixed inset-0 z-20 flex items-center justify-center bg-black/40 p-4"
          role="dialog"
        >
          <form
            class="w-full max-w-md space-y-3 rounded-lg bg-white p-4 shadow-xl"
            (ngSubmit)="confirmCancel(sale)"
          >
            <h3 class="font-semibold">Annuler la vente {{ sale.ticketNumber }}</h3>
            <input
              class="field"
              placeholder="Motif"
              [(ngModel)]="cancelReason"
              name="cancelReason"
              required
              maxlength="255"
            />
            @if (needsSupervisor()) {
              <p class="text-sm text-amber-900">
                Montant au-dessus du seuil ou caisse déjà clôturée : un superviseur doit saisir son
                e-mail et son code PIN.
              </p>
              <input
                class="field"
                type="email"
                placeholder="E-mail du superviseur"
                [(ngModel)]="supervisorEmail"
                name="supervisorEmail"
              />
              <input
                class="field"
                type="password"
                inputmode="numeric"
                placeholder="Code PIN"
                [(ngModel)]="supervisorPin"
                name="supervisorPin"
                autocomplete="off"
              />
            }
            <div class="flex justify-end gap-2">
              <button type="button" class="rounded border px-3 py-1" (click)="cancelling.set(null)">
                Fermer
              </button>
              <button type="submit" class="rounded bg-red-700 px-3 py-1 text-white">
                Annuler la vente
              </button>
            </div>
          </form>
        </div>
      }
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
  private readonly permissions = inject(PermissionService);
  private readonly apiBase = inject(APP_ENVIRONMENT).apiUrl;
  private readonly api = `${this.apiBase}/v1/cash/sessions`;

  protected readonly session = signal<CashSessionReport | null>(null);
  protected readonly recent = signal<CashSessionReport[]>([]);
  protected readonly sales = signal<SessionSale[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly policy = signal<CashPolicy>({
    cashDifferenceThresholdCents: 0,
    cancelApprovalThresholdCents: 0,
  });
  protected readonly cancelling = signal<SessionSale | null>(null);
  protected readonly needsSupervisor = computed(() => {
    const sale = this.cancelling();
    if (!sale || this.permissions.has('CASH_APPROVE')) {
      return false;
    }
    const threshold = this.policy().cancelApprovalThresholdCents;
    return (threshold > 0 && sale.totalNetCents > threshold) || this.session()?.status === 'CLOSED';
  });
  protected readonly countedTotal = signal(0);
  protected readonly expenseAccounts = EXPENSE_ACCOUNTS;
  protected readonly denominationValues = XOF_DENOMINATIONS;
  protected readonly mobileProviders = MOBILE_MONEY_PROVIDERS;
  protected readonly counts: Record<number, number | null> = {};
  protected readonly declared: Record<string, string> = {};
  protected floatAmount = '';
  protected notes = '';
  protected kind: 'EXPENSE' | 'CASH_OUT' | 'CASH_IN' = 'EXPENSE';
  protected expenseAccount = EXPENSE_ACCOUNTS[0].code;
  protected movementAmount = '';
  protected reason = '';
  protected policyDifference = '';
  protected policyCancel = '';
  protected cancelReason = '';
  protected supervisorEmail = '';
  protected supervisorPin = '';

  constructor() {
    void this.load();
    void this.loadPolicy();
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

  protected setCount(value: number, count: number | null): void {
    this.counts[value] = count;
    this.countedTotal.set(
      XOF_DENOMINATIONS.reduce((sum, v) => sum + v * Number(this.counts[v] ?? 0), 0),
    );
  }

  protected async select(report: CashSessionReport): Promise<void> {
    this.session.set(report);
    await this.loadSales(report);
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
    const denominations = XOF_DENOMINATIONS.filter((v) => Number(this.counts[v] ?? 0) > 0).map(
      (v) => ({
        value: v,
        count: Number(this.counts[v]),
      }),
    );
    const declared = [
      ...MOBILE_MONEY_PROVIDERS.filter((p) => this.declared[p]).map((p) => ({
        method: 'MOBILE_MONEY',
        provider: p,
        amountCents: this.currency.parse(this.declared[p]),
      })),
      ...(['CARD', 'CHECK'] as const)
        .filter((m) => this.declared[m])
        .map((m) => ({
          method: m,
          provider: null,
          amountCents: this.currency.parse(this.declared[m]),
        })),
    ];
    await this.run(() =>
      firstValueFrom(
        this.http.post<CashSessionReport>(`${this.api}/${current.sessionId}/closure`, {
          denominations,
          countedCashCents: denominations.length === 0 ? 0 : null,
          declared,
          notes: this.notes || null,
        }),
      ),
    );
  }

  protected async approve(s: CashSessionReport): Promise<void> {
    const note = prompt("Note d'approbation (optionnel)") ?? '';
    await this.run(() =>
      firstValueFrom(
        this.http.post<CashSessionReport>(`${this.api}/${s.sessionId}/approval`, {
          note: note || null,
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

  protected async savePolicy(): Promise<void> {
    this.error.set(null);
    try {
      this.policy.set(
        await firstValueFrom(
          this.http.put<CashPolicy>(`${this.apiBase}/v1/cash/policy`, {
            cashDifferenceThresholdCents: this.currency.parse(this.policyDifference),
            cancelApprovalThresholdCents: this.currency.parse(this.policyCancel),
          }),
        ),
      );
    } catch (error) {
      this.error.set((error as { error?: { detail?: string } }).error?.detail ?? 'Seuils refusés');
    }
  }

  protected startCancel(sale: SessionSale): void {
    this.cancelReason = '';
    this.supervisorEmail = '';
    this.supervisorPin = '';
    this.cancelling.set(sale);
  }

  protected async confirmCancel(sale: SessionSale): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(
        this.http.post(`${this.apiBase}/v1/sales/${sale.id}/cancellation`, {
          reason: this.cancelReason,
          terminalId: this.terminal.terminalId,
          supervisorEmail: this.supervisorEmail || null,
          supervisorPin: this.supervisorPin || null,
        }),
      );
      this.cancelling.set(null);
      const current = this.session();
      if (current) {
        this.session.set(
          await firstValueFrom(
            this.http.get<CashSessionReport>(`${this.api}/${current.sessionId}`),
          ),
        );
        await this.loadSales(current);
      }
    } catch (error) {
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Annulation refusée',
      );
    }
  }

  private async run(action: () => Promise<CashSessionReport>): Promise<void> {
    this.error.set(null);
    try {
      const report = await action();
      this.session.set(report);
      await this.loadSales(report);
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
      if (response.body) {
        await this.loadSales(response.body);
      }
    } catch {
      this.session.set(null);
    }
    await this.loadRecent();
  }

  private async loadSales(report: CashSessionReport): Promise<void> {
    try {
      const to = report.closedAt ?? new Date().toISOString();
      const list = await firstValueFrom(
        this.http.get<(SessionSale & { terminalId: string })[]>(`${this.apiBase}/v1/sales`, {
          params: { from: report.openedAt, to, limit: 500 },
        }),
      );
      this.sales.set(list.filter((sale) => sale.terminalId === report.terminalId));
    } catch {
      this.sales.set([]);
    }
  }

  private async loadPolicy(): Promise<void> {
    try {
      const policy = await firstValueFrom(
        this.http.get<CashPolicy>(`${this.apiBase}/v1/cash/policy`),
      );
      this.policy.set(policy);
      this.policyDifference = String(
        policy.cashDifferenceThresholdCents / this.currency.minorUnitsPerUnit(),
      );
      this.policyCancel = String(
        policy.cancelApprovalThresholdCents / this.currency.minorUnitsPerUnit(),
      );
    } catch {
      // policy stays at its defaults for users who may not read it
    }
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
