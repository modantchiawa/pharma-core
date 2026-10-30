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
import { TerminalLabelPipe } from '../../shared/terminal-label.pipe';
import { PaperFormat } from '../../core/api/models';
import { PosDatabaseService } from '../../core/offline/pos-database.service';
import { PrintSettingsService } from '../../core/printing/print-settings.service';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';
import { SkeletonRows } from '../../shared/ui/skeleton-rows';
import { FourEyesThresholdsService } from '../../core/control/four-eyes.service';
import { ToastService } from '../../core/ui/toast.service';
import { isFourEyesRefusal, secondPersonNeeded } from '../../shared/four-eyes';
import { Receipt, ReceiptData } from './receipt';
import { SaleReturnDialog } from './sale-return-dialog';

/** Detail of a recorded sale as the server returns it, enough to rebuild its ticket. */
interface SaleDetail {
  summary: {
    ticketNumber: string;
    terminalId: string;
    occurredAt: string;
    status: 'COMPLETED' | 'CANCELLED';
    certificationCode: string | null;
    certificationQr: string | null;
    origin: string;
  };
  totalGrossCents: number;
  totalDiscountCents: number;
  lines: {
    productId: string;
    quantity: number;
    unitPriceCents: number;
    discountCents: number;
    netCents: number;
    vatRate: number;
  }[];
  payments: { method: PaymentMethod; amountCents: number; provider: string | null }[];
}

const METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Espèces',
  CARD: 'Carte',
  CHECK: 'Chèque',
  THIRD_PARTY_PAYER: 'Tiers payant',
  VOUCHER: 'Avoir',
  MOBILE_MONEY: 'Mobile money',
  CREDIT: 'Crédit client',
};

const MOVEMENT_LABELS: Record<string, string> = {
  EXPENSE: 'Dépense',
  CASH_OUT: 'Remise en banque',
  CASH_IN: 'Apport en caisse',
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
  imports: [
    TerminalLabelPipe,
    FormsModule,
    DatePipe,
    MoneyPipe,
    HasPermissionDirective,
    Receipt,
    Icon,
    SaleReturnDialog,
    EmptyState,
    SkeletonRows,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (reprint(); as data) {
      <div class="hidden print:block"><app-receipt [data]="data" [format]="reprintFormat()" /></div>
    }
    <div class="page animate-in" [class.print:hidden]="reprint() !== null">
      <header class="page-header">
        <div>
          <h1 class="page-title">Caisse</h1>
          <p class="page-subtitle">
            Fond de caisse, rapport X, clôture à l'aveugle et approbation des écarts.
          </p>
        </div>
        <span class="badge h-7 px-3">
          <app-icon name="store" [size]="14" />
          Poste {{ terminal.terminalId | terminalLabel }}
        </span>
      </header>

      @if (session(); as s) {
        <div class="mb-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="receipt" [size]="16" /></span>Ventes nettes
            </div>
            <div class="kpi-value">{{ s.salesNetCents | money }}</div>
            <div class="kpi-foot">
              {{ s.salesCount }} vente(s) · {{ s.cancelledCount }} annulée(s)
            </div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="accounting" [size]="16" /></span>Espèces
              attendues
            </div>
            @if (s.expectedCashCents !== undefined) {
              <div class="kpi-value">{{ s.expectedCashCents | money }}</div>
              <div class="kpi-foot">Fond de caisse {{ s.openingFloatCents | money }} inclus</div>
            } @else {
              <div class="kpi-value flex items-center gap-2 text-subtle">
                <app-icon name="eyeOff" [size]="22" />Masquées
              </div>
              <div class="kpi-foot">Comptage à l'aveugle</div>
            }
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="coins" [size]="16" /></span>Espèces comptées
            </div>
            @if (s.countedCashCents !== undefined && s.countedCashCents !== null) {
              <div class="kpi-value">{{ s.countedCashCents | money }}</div>
              <div class="kpi-foot">Comptées à la clôture</div>
            } @else {
              <div class="kpi-value text-subtle">—</div>
              <div class="kpi-foot">Pas encore comptées</div>
            }
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="scale" [size]="16" /></span>Écart
            </div>
            @if (s.countedCashCents !== undefined && s.countedCashCents !== null) {
              <div
                class="kpi-value"
                [class.text-danger]="(s.differenceCents ?? 0) < 0"
                [class.text-success]="(s.differenceCents ?? 0) > 0"
              >
                {{ s.differenceCents ?? 0 | money }}
              </div>
              <div class="kpi-foot">
                @switch (s.approvalStatus) {
                  @case ('PENDING') {
                    <span class="badge badge-warning"><span class="dot"></span>À approuver</span>
                  }
                  @case ('APPROVED') {
                    <span class="badge badge-success"><span class="dot"></span>Approuvé</span>
                  }
                  @default {
                    Dans la tolérance
                  }
                }
              </div>
            } @else {
              <div class="kpi-value text-subtle">—</div>
              <div class="kpi-foot">Calculé à la clôture</div>
            }
          </div>
        </div>
      }

      <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
        <section class="min-w-0 space-y-5">
          @if (!loaded()) {
            <div class="card">
              <app-skeleton-rows [rows]="4" [figures]="2" />
            </div>
          } @else if (session(); as s) {
            <div class="card">
              <div class="card-header flex-wrap">
                <div class="min-w-0">
                  <div class="flex flex-wrap items-center gap-2">
                    <h2 class="card-title">
                      Session {{ s.status === 'OPEN' ? 'en cours' : 'clôturée' }} · poste
                      {{ s.terminalId | terminalLabel }}
                    </h2>
                    @if (s.status === 'OPEN') {
                      <span class="badge badge-success"><span class="dot"></span>Ouverte</span>
                    } @else {
                      <span class="badge"><app-icon name="lock" [size]="12" />Clôturée</span>
                    }
                  </div>
                  <p class="mt-1 text-sm text-muted">
                    Ouverte le {{ s.openedAt | date: 'dd/MM HH:mm' }} avec un fond de
                    {{ s.openingFloatCents | money }}
                    @if (s.closedAt) {
                      · clôturée le {{ s.closedAt | date: 'dd/MM HH:mm' }}
                    }
                  </p>
                </div>
                <button
                  type="button"
                  class="btn btn-secondary btn-sm print:hidden"
                  (click)="print()"
                >
                  <app-icon name="printer" [size]="16" />
                  Imprimer le rapport {{ s.status === 'OPEN' ? 'X' : 'Z' }}
                </button>
              </div>
              <div class="card-body space-y-5">
                @if (s.approvalStatus === 'PENDING') {
                  <div class="alert alert-warning flex-wrap items-center" role="status">
                    <app-icon name="alert" [size]="18" />
                    <span class="min-w-0 flex-1">
                      Écart au-dessus de la tolérance : en attente de l'approbation d'un
                      superviseur.
                    </span>
                    <button
                      *appHasPermission="'CASH_APPROVE'"
                      type="button"
                      class="btn btn-primary btn-sm"
                      (click)="approve(s)"
                    >
                      <app-icon name="check" [size]="16" />
                      Approuver
                    </button>
                  </div>
                } @else if (s.approvalStatus === 'APPROVED') {
                  <div class="alert alert-success">
                    <app-icon name="checkCircle" [size]="18" />
                    <span>
                      Clôture approuvée le {{ s.approvedAt | date: 'dd/MM HH:mm' }}
                      @if (s.approvalNote) {
                        · {{ s.approvalNote }}
                      }
                    </span>
                  </div>
                }

                <table class="w-full text-sm">
                  <tbody>
                    <tr class="border-b border-line">
                      <td class="py-2.5 font-medium">
                        Ventes ({{ s.salesCount }}, {{ s.cancelledCount }} annulée(s))
                      </td>
                      <td class="num py-2.5 font-semibold">{{ s.salesNetCents | money }}</td>
                    </tr>
                    @for (entry of methods(s); track entry[0]) {
                      <tr class="border-b border-line">
                        <td class="py-2 pl-4 text-muted">{{ label(entry[0]) }}</td>
                        <td class="num py-2">{{ entry[1] | money }}</td>
                      </tr>
                    }
                    @for (entry of providers(s); track entry[0]) {
                      <tr class="border-b border-line">
                        <td class="py-2 pl-8 text-subtle">{{ entry[0] }}</td>
                        <td class="num py-2 text-subtle">{{ entry[1] | money }}</td>
                      </tr>
                    }
                    <tr class="border-b border-line">
                      <td class="py-2">Encaissements de créances (espèces)</td>
                      <td class="num py-2">{{ s.customerPaymentsCashCents | money }}</td>
                    </tr>
                    <tr class="border-b border-line">
                      <td class="py-2">Apports en caisse</td>
                      <td class="num py-2">{{ s.cashInCents | money }}</td>
                    </tr>
                    <tr class="border-b border-line">
                      <td class="py-2">Sorties (banque, remboursements)</td>
                      <td class="num py-2">-{{ s.cashOutCents | money }}</td>
                    </tr>
                    <tr class="border-b border-line">
                      <td class="py-2">Dépenses</td>
                      <td class="num py-2">-{{ s.expensesCents | money }}</td>
                    </tr>
                    @if (s.expectedCashCents !== undefined) {
                      <tr class="bg-surface-2 font-semibold">
                        <td class="rounded-l-[var(--radius-sm)] px-3 py-2.5">
                          Espèces attendues en caisse
                        </td>
                        <td class="num rounded-r-[var(--radius-sm)] px-3 py-2.5">
                          {{ s.expectedCashCents | money }}
                        </td>
                      </tr>
                    } @else {
                      <tr class="text-muted">
                        <td class="py-2.5" colspan="2">
                          <span class="inline-flex items-center gap-2">
                            <app-icon name="eyeOff" [size]="16" />
                            Espèces attendues masquées : comptez la caisse à l'aveugle.
                          </span>
                        </td>
                      </tr>
                    }
                    @if (s.countedCashCents !== undefined && s.countedCashCents !== null) {
                      <tr class="border-b border-line">
                        <td class="py-2">Espèces comptées</td>
                        <td class="num py-2">{{ s.countedCashCents | money }}</td>
                      </tr>
                      <tr
                        class="font-semibold"
                        [class.text-danger]="(s.differenceCents ?? 0) < 0"
                        [class.text-success]="(s.differenceCents ?? 0) > 0"
                      >
                        <td class="py-2">Écart</td>
                        <td class="num py-2">{{ s.differenceCents ?? 0 | money }}</td>
                      </tr>
                    }
                  </tbody>
                </table>

                @if (s.reconciliation.length > 0) {
                  <div>
                    <h3 class="mb-2 text-sm font-semibold">Rapprochement par mode de paiement</h3>
                    <div class="overflow-x-auto rounded-[var(--radius)] border border-line">
                      <table class="data-table whitespace-nowrap">
                        <thead>
                          <tr>
                            <th>Mode</th>
                            <th class="text-right">Attendu</th>
                            <th class="text-right">Déclaré</th>
                            <th class="text-right">Écart</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (r of s.reconciliation; track r.method + (r.provider ?? '')) {
                            <tr [class.text-danger]="r.differenceCents !== 0">
                              <td>
                                {{ label(r.method) }}{{ r.provider ? ' · ' + r.provider : '' }}
                              </td>
                              <td class="num">{{ r.expectedCents | money }}</td>
                              <td class="num">{{ r.declaredCents | money }}</td>
                              <td class="num font-semibold">{{ r.differenceCents | money }}</td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </div>
                }

                @if (s.countedBreakdown.length > 0) {
                  <div class="flex flex-wrap items-center gap-1.5 text-xs text-muted">
                    <span class="mr-1 font-medium">Coupures :</span>
                    @for (d of s.countedBreakdown; track d.valueCents) {
                      <span class="badge">{{ d.count }} × {{ d.valueCents | money }}</span>
                    }
                  </div>
                }

                @if (s.movements.length > 0) {
                  <div>
                    <h3 class="mb-2 text-sm font-semibold">Mouvements</h3>
                    <ul
                      class="divide-y divide-[var(--border)] rounded-[var(--radius)] border border-line text-sm"
                    >
                      @for (m of s.movements; track m.id) {
                        <li class="flex items-center justify-between gap-3 px-4 py-2.5">
                          <span class="flex min-w-0 flex-wrap items-center gap-2">
                            <span class="text-muted tabular-nums">{{
                              m.occurredAt | date: 'HH:mm'
                            }}</span>
                            <span class="badge">{{ movementLabel(m.kind) }}</span>
                            <span class="truncate">{{ m.reason }}</span>
                          </span>
                          <span class="num font-medium">{{ m.amountCents | money }}</span>
                        </li>
                      }
                    </ul>
                  </div>
                }

                @if (s.reportHash) {
                  <p
                    class="break-all rounded-[var(--radius-sm)] bg-surface-2 px-3 py-2 font-mono text-xs text-subtle"
                  >
                    Empreinte Z {{ s.reportHash }}<br />précédente {{ s.previousReportHash }}
                  </p>
                }
              </div>
            </div>

            <div class="card">
              <div class="card-header">
                <h2 class="card-title">Ventes de la session</h2>
                <span class="badge">{{ sales().length }}</span>
              </div>
              @if (salesLoading() && sales().length === 0) {
                <app-skeleton-rows [rows]="5" [figures]="2" />
              } @else if (sales().length > 0) {
                <div class="content-in overflow-x-auto">
                  <table class="data-table whitespace-nowrap">
                    <thead>
                      <tr>
                        <th>Heure</th>
                        <th>Ticket</th>
                        <th class="text-right">Montant</th>
                        <th class="text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (sale of sales(); track sale.id) {
                        <tr [class.text-subtle]="sale.status === 'CANCELLED'">
                          <td class="tabular-nums">{{ sale.occurredAt | date: 'HH:mm' }}</td>
                          <td class="font-medium">{{ sale.ticketNumber }}</td>
                          <td class="num" [class.line-through]="sale.status === 'CANCELLED'">
                            {{ sale.totalNetCents | money }}
                          </td>
                          <td>
                            <div class="flex items-center justify-end gap-1 print:hidden">
                              <button
                                type="button"
                                class="btn btn-ghost btn-sm"
                                (click)="reprintSale(sale, false)"
                                title="Réimprimer le ticket"
                              >
                                <app-icon name="receipt" [size]="16" />
                                Ticket
                              </button>
                              @if (printing.settings().invoicePaper === 'A4') {
                                <button
                                  type="button"
                                  class="btn btn-ghost btn-sm"
                                  (click)="reprintSale(sale, true)"
                                  title="Facture A4"
                                >
                                  <app-icon name="prescription" [size]="16" />
                                  Facture
                                </button>
                              }
                              @if (sale.status === 'COMPLETED') {
                                <button
                                  *appHasPermission="'SALE_CANCEL'"
                                  type="button"
                                  class="btn btn-ghost btn-sm"
                                  (click)="returning.set(sale)"
                                  title="Retour partiel remboursé en espèces"
                                >
                                  <app-icon name="refresh" [size]="16" />
                                  Retour
                                </button>
                                <button
                                  *appHasPermission="'SALE_CANCEL'"
                                  type="button"
                                  class="btn btn-ghost btn-sm text-danger"
                                  (click)="startCancel(sale)"
                                >
                                  <app-icon name="ban" [size]="16" />
                                  Annuler
                                </button>
                              } @else {
                                <span class="badge badge-danger">annulée</span>
                              }
                            </div>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <app-empty-state illustration="receipt">
                  <p>Aucune vente</p>
                </app-empty-state>
              }
            </div>
          } @else {
            <div class="card">
              <div class="empty-state">
                <app-icon name="cash" [size]="22" />
                <p class="font-medium text-ink">
                  Aucune session ouverte sur le poste {{ terminal.terminalId | terminalLabel }}.
                </p>
                <p class="text-subtle">Ouvrez la caisse avec un fond pour commencer à encaisser.</p>
              </div>
            </div>
          }

          <div class="card">
            <div class="card-header">
              <h2 class="card-title flex items-center gap-2">
                <app-icon name="history" [size]="16" class="text-muted" />Sessions récentes
              </h2>
            </div>
            @if (recent().length > 0) {
              <div class="overflow-x-auto">
                <table class="data-table whitespace-nowrap">
                  <thead>
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
                      <tr
                        class="cursor-pointer"
                        [class.bg-brand-soft]="session()?.sessionId === r.sessionId"
                        (click)="select(r)"
                      >
                        <td class="font-medium">{{ r.terminalId | terminalLabel }}</td>
                        <td class="tabular-nums">{{ r.openedAt | date: 'dd/MM HH:mm' }}</td>
                        <td class="tabular-nums">
                          @if (r.closedAt) {
                            {{ r.closedAt | date: 'dd/MM HH:mm' }}
                          } @else {
                            <span class="badge badge-success"
                              ><span class="dot"></span>ouverte</span
                            >
                          }
                        </td>
                        <td class="num">{{ r.salesNetCents | money }}</td>
                        <td
                          class="num"
                          [class.text-danger]="(r.differenceCents ?? 0) < 0"
                          [class.text-success]="(r.differenceCents ?? 0) > 0"
                        >
                          {{ r.differenceCents ?? 0 | money }}
                        </td>
                        <td class="text-right">
                          @if (r.approvalStatus === 'PENDING') {
                            <span class="badge badge-warning">à approuver</span>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <div class="empty-state">
                <app-icon name="history" [size]="22" />
                <p>Aucune session récente</p>
              </div>
            }
          </div>
        </section>

        <aside class="space-y-5 print:hidden" *appHasPermission="'CASH_SESSION_MANAGE'">
          @if (error(); as text) {
            <div class="alert alert-danger" role="alert">
              <app-icon name="error" [size]="18" />
              <span>{{ text }}</span>
            </div>
          }
          @if (!session() || session()?.status === 'CLOSED') {
            <form class="card" (ngSubmit)="open()">
              <div class="card-header">
                <h3 class="card-title">Ouvrir la caisse</h3>
                <app-icon name="cash" [size]="18" class="text-subtle" />
              </div>
              <div class="card-body space-y-4">
                <label class="block">
                  <span class="label">Fond de caisse</span>
                  <input
                    inputmode="decimal"
                    class="input text-right tabular-nums"
                    [(ngModel)]="floatAmount"
                    name="float"
                    placeholder="0"
                  />
                  <span class="hint block">Espèces présentes dans le tiroir à l'ouverture.</span>
                </label>
                <button type="submit" class="btn btn-primary w-full">
                  <app-icon name="cash" [size]="16" />
                  Ouvrir
                </button>
              </div>
            </form>
          } @else {
            <form class="card" (ngSubmit)="movement()" *appHasPermission="'CASH_EXPENSE'">
              <div class="card-header">
                <h3 class="card-title">Mouvement de caisse</h3>
                <app-icon name="transfer" [size]="18" class="text-subtle" />
              </div>
              <div class="card-body space-y-3">
                <label class="block">
                  <span class="label">Type</span>
                  <select class="select" [(ngModel)]="kind" name="kind">
                    <option value="EXPENSE">Dépense</option>
                    <option value="CASH_OUT">Remise en banque</option>
                    <option value="CASH_IN">Apport en caisse</option>
                  </select>
                </label>
                @if (kind === 'EXPENSE') {
                  <label class="block">
                    <span class="label">Compte de charge</span>
                    <select class="select" [(ngModel)]="expenseAccount" name="account">
                      @for (a of expenseAccounts; track a.code) {
                        <option [value]="a.code">{{ a.code }} {{ a.label }}</option>
                      }
                    </select>
                  </label>
                }
                <div class="grid grid-cols-[8rem_minmax(0,1fr)] gap-2">
                  <input
                    inputmode="decimal"
                    class="input text-right tabular-nums"
                    placeholder="Montant"
                    [(ngModel)]="movementAmount"
                    name="amount"
                    required
                  />
                  <input
                    class="input"
                    placeholder="Motif"
                    [(ngModel)]="reason"
                    name="reason"
                    required
                    maxlength="255"
                  />
                </div>
                <button type="submit" class="btn btn-secondary w-full">
                  <app-icon name="check" [size]="16" />
                  Enregistrer
                </button>
              </div>
            </form>
            <form class="card" (ngSubmit)="close()">
              <div class="card-header">
                <div>
                  <h3 class="card-title">Clôturer la caisse (rapport Z)</h3>
                  <p class="mt-0.5 text-xs text-muted">
                    Comptez les coupures ; le total se calcule seul.
                  </p>
                </div>
                <app-icon name="lock" [size]="18" class="text-subtle" />
              </div>
              <div class="card-body space-y-4">
                <div class="grid grid-cols-3 gap-2">
                  @for (value of denominationValues; track value) {
                    <label class="block min-w-0">
                      <span class="block truncate text-[0.7rem] font-medium text-muted">{{
                        value | money
                      }}</span>
                      <input
                        type="number"
                        min="0"
                        class="input mt-1 !min-h-9 text-right tabular-nums"
                        placeholder="0"
                        [ngModel]="counts[value] ?? null"
                        (ngModelChange)="setCount(value, $event)"
                        [name]="'d' + value"
                      />
                    </label>
                  }
                </div>
                <div
                  class="flex items-center justify-between rounded-[var(--radius)] bg-brand-soft px-4 py-3"
                >
                  <span class="text-sm font-medium text-muted">Espèces comptées</span>
                  <span class="text-lg font-bold tabular-nums">{{ countedTotal() | money }}</span>
                </div>
                <div class="space-y-2">
                  <h4 class="text-sm font-semibold">Montants déclarés (relevés)</h4>
                  @for (p of mobileProviders; track p) {
                    <label class="grid grid-cols-[8rem_minmax(0,1fr)] items-center gap-2 text-sm">
                      <span class="truncate text-muted">{{ p.replace('_', ' ') }}</span>
                      <input
                        inputmode="decimal"
                        class="input text-right tabular-nums"
                        [(ngModel)]="declared[p]"
                        [name]="'mm' + p"
                        placeholder="0"
                      />
                    </label>
                  }
                  <label class="grid grid-cols-[8rem_minmax(0,1fr)] items-center gap-2 text-sm">
                    <span class="text-muted">Carte</span>
                    <input
                      inputmode="decimal"
                      class="input text-right tabular-nums"
                      [(ngModel)]="declared['CARD']"
                      name="card"
                      placeholder="0"
                    />
                  </label>
                  <label class="grid grid-cols-[8rem_minmax(0,1fr)] items-center gap-2 text-sm">
                    <span class="text-muted">Chèques</span>
                    <input
                      inputmode="decimal"
                      class="input text-right tabular-nums"
                      [(ngModel)]="declared['CHECK']"
                      name="check"
                      placeholder="0"
                    />
                  </label>
                </div>
                <textarea
                  class="textarea"
                  rows="2"
                  placeholder="Observations"
                  [(ngModel)]="notes"
                  name="notes"
                ></textarea>
                <button type="submit" class="btn btn-primary w-full">
                  <app-icon name="lock" [size]="16" />
                  Clôturer
                </button>
              </div>
            </form>
          }
          <form class="card" (ngSubmit)="savePolicy()" *appHasPermission="'CASH_APPROVE'">
            <div class="card-header">
              <h3 class="card-title">Seuils d'approbation</h3>
              <app-icon name="insurance" [size]="18" class="text-subtle" />
            </div>
            <div class="card-body space-y-3">
              <label class="block">
                <span class="label">Tolérance d'écart de caisse (0 = aucune approbation)</span>
                <input
                  inputmode="decimal"
                  class="input text-right tabular-nums"
                  [(ngModel)]="policyDifference"
                  name="policyDifference"
                />
              </label>
              <label class="block">
                <span class="label">Annulation à approuver au-dessus de</span>
                <input
                  inputmode="decimal"
                  class="input text-right tabular-nums"
                  [(ngModel)]="policyCancel"
                  name="policyCancel"
                />
              </label>
              <button type="submit" class="btn btn-secondary w-full">Enregistrer les seuils</button>
            </div>
          </form>
        </aside>
      </div>

      @if (cancelling(); as sale) {
        <div
          class="fixed inset-0 z-50 flex items-center justify-center bg-[rgb(2_6_12/0.5)] p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
        >
          <form
            class="card w-full max-w-md shadow-[var(--shadow-lg)] animate-in"
            (ngSubmit)="confirmCancel(sale)"
          >
            <div class="card-header">
              <h3 class="card-title">Annuler la vente {{ sale.ticketNumber }}</h3>
              <span class="badge">{{ sale.totalNetCents | money }}</span>
            </div>
            <div class="card-body space-y-3">
              <label class="block">
                <span class="label">Motif</span>
                <input
                  class="input"
                  placeholder="Motif"
                  [(ngModel)]="cancelReason"
                  name="cancelReason"
                  required
                  maxlength="255"
                />
              </label>
              @if (needsSupervisor()) {
                <div class="alert alert-warning">
                  <app-icon name="lock" [size]="18" />
                  <span>
                    Montant au-dessus du seuil, caisse déjà clôturée ou stupéfiant : une seconde
                    personne habilitée (titulaire ou adjoint) saisit son e-mail et son code PIN.
                  </span>
                </div>
                <input
                  class="input"
                  type="email"
                  placeholder="E-mail de la seconde personne"
                  [(ngModel)]="supervisorEmail"
                  name="supervisorEmail"
                />
                <input
                  class="input"
                  type="password"
                  inputmode="numeric"
                  placeholder="Code PIN"
                  [(ngModel)]="supervisorPin"
                  name="supervisorPin"
                  autocomplete="off"
                />
              }
              <div class="flex justify-end gap-2 pt-2">
                <button type="button" class="btn btn-secondary" (click)="cancelling.set(null)">
                  Fermer
                </button>
                <button type="submit" class="btn btn-danger">
                  <app-icon name="ban" [size]="16" />
                  Annuler la vente
                </button>
              </div>
            </div>
          </form>
        </div>
      }
    </div>

    @if (returning(); as sale) {
      <app-sale-return-dialog
        [saleId]="sale.id"
        [ticket]="sale.ticketNumber"
        [terminalId]="terminal.terminalId"
        (closed)="returning.set(null)"
        (done)="afterReturn($event.refundCents)"
      />
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

  protected readonly printing = inject(PrintSettingsService);
  private readonly databases = inject(PosDatabaseService);
  protected readonly reprint = signal<ReceiptData | null>(null);
  protected readonly reprintFormat = signal<PaperFormat>('MM80');

  protected readonly session = signal<CashSessionReport | null>(null);
  protected readonly recent = signal<CashSessionReport[]>([]);
  protected readonly sales = signal<SessionSale[]>([]);
  /** Placeholders until the open session (or its absence) is known, then while its sales load. */
  protected readonly loaded = signal(false);
  protected readonly salesLoading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly policy = signal<CashPolicy>({
    cashDifferenceThresholdCents: 0,
    cancelApprovalThresholdCents: 0,
  });
  protected readonly cancelling = signal<SessionSale | null>(null);
  protected readonly returning = signal<SessionSale | null>(null);
  private readonly fourEyes = inject(FourEyesThresholdsService);
  private readonly toast = inject(ToastService);
  /** The server asked for a second signature (narcotic on the ticket, four-eyes threshold). */
  private readonly approvalForced = signal(false);
  protected readonly needsSupervisor = computed(() => {
    const sale = this.cancelling();
    if (!sale) {
      return false;
    }
    if (
      this.approvalForced() ||
      secondPersonNeeded(this.fourEyes.thresholds(), 'CANCELLATION', sale.totalNetCents, false)
    ) {
      return true;
    }
    if (this.permissions.has('CASH_APPROVE')) {
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

  protected movementLabel(kind: string): string {
    return MOVEMENT_LABELS[kind] ?? kind;
  }

  protected methods(s: CashSessionReport): [string, number][] {
    return Object.entries(s.byPaymentMethod) as [string, number][];
  }

  protected providers(s: CashSessionReport): [string, number][] {
    return Object.entries(s.mobileMoneyByProvider);
  }

  /** X/Z report through the browser, on the register's ticket paper. */
  protected print(): void {
    this.reprint.set(null);
    this.printing.printPage(this.printing.settings().paper);
  }

  /**
   * Ticket or invoice of a recorded sale: the thermal printer when the register has one (ticket
   * only), otherwise the browser with the ticket rebuilt from the sale detail and the local
   * product cache.
   */
  protected async reprintSale(sale: SessionSale, invoice: boolean): Promise<void> {
    this.error.set(null);
    if (!invoice && (await this.printing.printSale(sale.id))) {
      return;
    }
    try {
      const detail = await firstValueFrom(
        this.http.get<SaleDetail>(`${this.apiBase}/v1/sales/${sale.id}`),
      );
      const products = this.databases.db().products;
      const lines = await Promise.all(
        detail.lines.map(async (line) => {
          const cached = await products.get(line.productId);
          return {
            productId: line.productId,
            designation: cached?.designation ?? `Produit ${line.productId.slice(0, 8)}`,
            cip13: cached?.cip13 ?? '',
            listing: cached?.listing ?? 'NONE',
            vatRate: line.vatRate,
            unitPriceCents: line.unitPriceCents,
            quantity: line.quantity,
            discountCents: line.discountCents,
            availableQuantity: cached?.availableQuantity ?? 0,
          };
        }),
      );
      const byRate = new Map<number, number>();
      for (const line of detail.lines) {
        byRate.set(line.vatRate, (byRate.get(line.vatRate) ?? 0) + line.netCents);
      }
      const net = detail.lines.reduce((sum, line) => sum + line.netCents, 0);
      const paid = detail.payments.reduce((sum, payment) => sum + payment.amountCents, 0);
      this.reprint.set({
        ticketNumber: detail.summary.ticketNumber,
        terminalId: detail.summary.terminalId,
        occurredAt: new Date(detail.summary.occurredAt),
        cashier: this.permissions.profile()?.displayName ?? '',
        lines,
        payments: detail.payments.map((payment) => ({
          method: payment.method,
          amountCents: payment.amountCents,
          provider: payment.provider ?? undefined,
        })),
        grossCents: detail.totalGrossCents,
        discountCents: detail.totalDiscountCents,
        netCents: net,
        changeDueCents: Math.max(0, paid - net),
        vat: [...byRate.entries()]
          .sort(([a], [b]) => a - b)
          .map(([rate, totalTtc]) => ({
            rate,
            totalTtc,
            vat: Math.round(totalTtc - totalTtc / (1 + rate / 100)),
          })),
        offline: detail.summary.origin === 'OFFLINE_REPLAY',
        cancelled: detail.summary.status === 'CANCELLED',
        certificationCode: detail.summary.certificationCode,
        certificationQr: detail.summary.certificationQr,
      });
      const paper = invoice ? 'A4' : this.printing.settings().paper;
      this.reprintFormat.set(paper);
      this.printing.printPage(paper);
    } catch {
      this.error.set('Ticket indisponible : la vente n’a pas pu être relue');
    }
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
    void this.fourEyes.load();
    this.approvalForced.set(false);
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
      if (isFourEyesRefusal(error)) {
        this.approvalForced.set(true);
        this.supervisorPin = '';
      }
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Annulation refusée',
      );
    }
  }

  protected async afterReturn(refundCents: number): Promise<void> {
    this.returning.set(null);
    const refund = new Intl.NumberFormat('fr-FR', {
      style: 'currency',
      currency: this.currency.current(),
    }).format(this.currency.toUnits(refundCents));
    this.toast.success(`Retour enregistré : ${refund} remboursés en espèces`);
    const current = this.session();
    if (current) {
      this.session.set(
        await firstValueFrom(this.http.get<CashSessionReport>(`${this.api}/${current.sessionId}`)),
      );
      await this.loadSales(current);
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
    this.loaded.set(true);
    await this.loadRecent();
  }

  private async loadSales(report: CashSessionReport): Promise<void> {
    this.salesLoading.set(true);
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
    } finally {
      this.salesLoading.set(false);
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
