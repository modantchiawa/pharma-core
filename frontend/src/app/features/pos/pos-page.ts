import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  Customer,
  InsurancePolicy,
  MOBILE_MONEY_PROVIDERS,
  PaymentMethod,
  Prescription,
} from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { PermissionService } from '../../core/auth/permission.service';
import { CatalogSyncService } from '../../core/offline/catalog-sync.service';
import { ConnectivityService } from '../../core/offline/connectivity.service';
import { CachedProduct } from '../../core/offline/pos-database';
import { PosDatabaseService } from '../../core/offline/pos-database.service';
import { SaleOutboxService } from '../../core/offline/sale-outbox.service';
import { TerminalService } from '../../core/offline/terminal.service';
import { LicenseStateService } from '../../core/subscription/license-state.service';
import { saleRefusal } from '../../core/subscription/license-text';
import { uuidv7 } from '../../core/util/uuid';
import { parseGs1 } from '../../shared/gs1';
import { MoneyPipe } from '../../shared/money.pipe';
import { ToastService } from '../../core/ui/toast.service';
import { CartStore, discountPercent } from './cart.store';
import { PaperFormat, TerminalTicketRequest } from '../../core/api/models';
import { PrintSettingsService } from '../../core/printing/print-settings.service';
import { PAYMENT_LABELS, Receipt, ReceiptData } from './receipt';
import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons';
import { LocationChip } from '../../shared/ui/location-chip';
import { placeOf } from '../../shared/storage-location';
import { ProductThumb } from '../../shared/ui/product-thumb';
import { IdleService } from '../../core/session/idle.service';
import { SoundService } from '../../core/ui/sound.service';
import { AnimatedNumber } from '../../shared/ui/animated-number';
import { EmptyState } from '../../shared/ui/empty-state';
import { withExits } from '../../shared/ui/exit-list';
import { MotionFeedback } from '../../shared/ui/motion-feedback';

/** What the success banner of the last recorded sale shows. */
interface SaleDone {
  ticketNumber: string;
  netCents: number;
  changeDueCents: number;
}

/** The banner leaves on the next scan, or after this delay (longer when change is due). */
const SALE_DONE_MS = 4000;
const SALE_DONE_WITH_CHANGE_MS = 7000;
const BURST = [0, 45, 90, 135, 180, 225, 270, 315];

/**
 * Counter sale screen designed for keyboard and barcode scanner: the scanner types the EAN-13
 * followed by Enter. Shortcuts: F2 search, F4 cash payment of the remainder, F9 validate, Esc clear.
 */
@Component({
  selector: 'app-pos-page',
  imports: [
    FormsModule,
    MoneyPipe,
    Receipt,
    Icon,
    LocationChip,
    ProductThumb,
    AnimatedNumber,
    EmptyState,
    MotionFeedback,
  ],
  providers: [CartStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'onShortcut($event)' },
  template: `
    <div
      class="grid grid-cols-1 gap-4 p-4 lg:h-full lg:grid-cols-[minmax(0,1fr)_400px] lg:p-5 print:hidden"
    >
      <section class="flex min-h-0 min-w-0 flex-col gap-3">
        <div class="relative">
          <label class="scan input-with-icon">
            <app-icon name="barcode" [size]="22" />
            <input
              #search
              type="search"
              autocomplete="off"
              autofocus
              class="input"
              placeholder="Scanner un code-barres ou rechercher un produit"
              [(ngModel)]="term"
              (ngModelChange)="lookup($event)"
              (keydown.enter)="scan()"
              (keydown.arrowdown)="move(1)"
              (keydown.arrowup)="move(-1)"
              aria-label="Recherche produit"
            />
            <span class="kbd pointer-events-none absolute top-1/2 right-4 -translate-y-1/2"
              >F2</span
            >
          </label>
          @if (results().length > 0) {
            <ul
              class="results absolute z-20 mt-2 max-h-96 w-full overflow-auto p-1.5"
              role="listbox"
            >
              @for (product of results(); track product.productId; let i = $index) {
                <li
                  role="option"
                  [attr.aria-selected]="i === highlighted()"
                  class="flex cursor-pointer items-center gap-3 rounded-[var(--radius-sm)] px-3 py-2.5"
                  (mousedown)="pick(product)"
                >
                  <app-product-thumb
                    [productId]="product.productId"
                    [version]="product.imageVersion"
                    [name]="product.designation"
                    [size]="40"
                  />
                  <span class="min-w-0 flex-1">
                    <span class="block truncate font-medium">{{ product.designation }}</span>
                    <span
                      class="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-subtle"
                    >
                      <span class="tabular-nums">{{ product.cip13 }}</span>
                      @if (placeOf(product); as place) {
                        <app-location-chip [place]="place" />
                      }
                    </span>
                  </span>
                  @if (product.availableQuantity === 0 && product.dci) {
                    <button
                      type="button"
                      class="btn btn-soft btn-sm"
                      (mousedown)="$event.stopPropagation(); showAlternatives(product)"
                    >
                      <app-icon name="transfer" [size]="14" />
                      Équivalents
                    </button>
                  }
                  <span
                    class="flex shrink-0 flex-col items-end gap-1 sm:flex-row sm:items-center sm:gap-3"
                  >
                    <span
                      class="badge"
                      [class.badge-success]="product.availableQuantity > 5"
                      [class.badge-warning]="
                        product.availableQuantity > 0 && product.availableQuantity <= 5
                      "
                      [class.badge-danger]="product.availableQuantity === 0"
                    >
                      stock {{ product.availableQuantity }}
                    </span>
                    <span class="num font-semibold sm:w-28">{{
                      product.unitPriceCents | money
                    }}</span>
                  </span>
                </li>
              }
            </ul>
          }
        </div>
        @if (message(); as text) {
          <div class="alert alert-warning" role="alert">
            <app-icon name="alert" [size]="18" />
            <span>{{ text }}</span>
          </div>
        }

        <div class="card flex min-h-0 flex-1 flex-col overflow-hidden">
          <div class="card-header">
            <div class="flex items-center gap-2">
              <app-icon name="cart" [size]="18" class="text-muted" />
              <h2 class="card-title">Panier</h2>
              @if (cart.itemCount() > 0) {
                <span
                  class="pop-enter badge badge-brand"
                  [appFeedback]="cart.itemCount()"
                  feedbackKind="pop"
                  >{{ cart.itemCount() }} article(s)</span
                >
              }
            </div>
            @if (cart.lines().length > 0) {
              <span class="hidden text-xs text-subtle sm:inline">
                <span class="kbd">Échap</span> vider le panier
              </span>
            }
          </div>
          <div class="min-h-0 flex-1 overflow-auto">
            @if (lines().length > 0) {
              <table class="data-table">
                <thead class="sticky top-0 z-10">
                  <tr>
                    <th>Produit</th>
                    <th class="w-36">Qté</th>
                    <th class="w-28">Remise</th>
                    <th class="num w-32">Montant</th>
                    <th class="w-12"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (entry of lines(); track entry.item.productId) {
                    @let line = entry.item;
                    <tr
                      class="row-enter flash-surface"
                      [class.row-leave]="entry.leaving"
                      [attr.aria-hidden]="entry.leaving ? 'true' : null"
                      [appFeedback]="line.quantity"
                      [feedbackOnInit]="true"
                    >
                      <td class="relative !pl-[3.75rem]">
                        <app-product-thumb
                          class="!absolute top-1/2 left-4 -translate-y-1/2"
                          [productId]="line.productId"
                          [version]="line.imageVersion"
                          [name]="line.designation"
                          [size]="36"
                        />
                        <div class="flex items-center gap-2 font-medium">
                          {{ line.designation }}
                          @if (line.listing !== 'NONE') {
                            <span class="badge badge-danger">{{ line.listing }}</span>
                          }
                        </div>
                        <div
                          class="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-subtle"
                        >
                          <span>{{ line.unitPriceCents | money }} · TVA {{ line.vatRate }} %</span>
                          @if (line.place; as place) {
                            <app-location-chip [place]="place" />
                          }
                        </div>
                      </td>
                      <td>
                        <div class="stepper">
                          <button
                            type="button"
                            (click)="cart.setQuantity(line.productId, line.quantity - 1)"
                            [attr.aria-label]="'Diminuer ' + line.designation"
                          >
                            <app-icon name="minus" [size]="14" />
                          </button>
                          <input
                            type="number"
                            min="0"
                            [appFeedback]="line.quantity"
                            feedbackKind="pulse"
                            [ngModel]="line.quantity"
                            (ngModelChange)="cart.setQuantity(line.productId, $event)"
                            [attr.aria-label]="'Quantité ' + line.designation"
                          />
                          <button
                            type="button"
                            (click)="cart.setQuantity(line.productId, line.quantity + 1)"
                            [attr.aria-label]="'Augmenter ' + line.designation"
                          >
                            <app-icon name="plus" [size]="14" />
                          </button>
                        </div>
                      </td>
                      <td>
                        <div class="relative">
                          <input
                            type="number"
                            min="0"
                            max="100"
                            step="0.5"
                            class="input h-9 pr-7"
                            [ngModel]="percent(line)"
                            (ngModelChange)="cart.setDiscountPercent(line.productId, $event)"
                            [attr.aria-label]="'Remise ' + line.designation"
                          />
                          <span
                            class="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-xs text-subtle"
                            >%</span
                          >
                        </div>
                      </td>
                      <td class="num font-semibold">
                        {{ line.unitPriceCents * line.quantity - line.discountCents | money }}
                      </td>
                      <td>
                        <button
                          type="button"
                          class="btn btn-ghost btn-icon btn-sm text-muted hover:!text-danger"
                          (click)="cart.remove(line.productId)"
                          aria-label="Retirer"
                        >
                          <app-icon name="trash" [size]="16" />
                        </button>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            } @else {
              <app-empty-state illustration="cart" class="h-full justify-center">
                <p class="text-base font-medium text-ink">Panier vide : scannez un produit</p>
                <p class="flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs">
                  <span><span class="kbd">F2</span> rechercher</span>
                  <span><span class="kbd">F4</span> espèces</span>
                  <span><span class="kbd">F5</span> mobile money</span>
                  <span><span class="kbd">F9</span> valider</span>
                </p>
              </app-empty-state>
            }
          </div>
        </div>
      </section>

      <aside class="card flex min-h-0 flex-col overflow-hidden">
        <div class="total px-5 py-4">
          <p class="text-xs font-semibold tracking-wide text-muted uppercase">Total à payer</p>
          <p class="mt-1 text-4xl font-bold tracking-tight text-brand">
            <app-animated-number [value]="cart.netCents()" [live]="true" />
          </p>
          <p class="mt-1 text-sm text-muted">
            {{ cart.itemCount() }} article(s)
            @if (cart.discountCents() > 0) {
              · dont remises {{ cart.discountCents() | money }}
            }
          </p>
          @for (banner of saleBanner(); track banner.item.ticketNumber) {
            @let done = banner.item;
            <div
              class="sale-done"
              [class.sale-done-leave]="banner.leaving"
              aria-hidden="true"
              data-testid="pos-sale-done"
              (click)="saleDone.set(null)"
            >
              <span class="sale-check">
                <svg viewBox="0 0 52 52" width="52" height="52">
                  <circle class="sale-check-disc" cx="26" cy="26" r="24" />
                  <circle class="sale-check-ring" cx="26" cy="26" r="24" pathLength="1" />
                  <path class="sale-check-mark" d="M15.5 27l7 7 14-15" pathLength="1" />
                </svg>
                @for (angle of burst; track angle) {
                  <i class="sale-spark" [style.--a]="angle + 'deg'"></i>
                }
              </span>
              <span class="min-w-0 flex-1">
                <span class="sale-done-title">Vente {{ done.ticketNumber }} enregistrée</span>
                @if (done.changeDueCents > 0) {
                  <span class="sale-done-label">Rendu monnaie</span>
                  <span class="sale-done-amount">{{ done.changeDueCents | money }}</span>
                } @else {
                  <span class="sale-done-label">Encaissé</span>
                  <span class="sale-done-amount">{{ done.netCents | money }}</span>
                }
              </span>
            </div>
          }
        </div>

        <div class="min-h-0 flex-1 space-y-4 overflow-auto p-4">
          @if (cart.requiresPrescription()) {
            <fieldset class="panel space-y-2">
              <legend class="panel-title">
                <app-icon name="prescription" [size]="16" />
                Ordonnance
                <span class="badge badge-warning ml-1">requise</span>
              </legend>
              @if (prescriptionsOnFile().length > 0) {
                <select
                  class="select"
                  [ngModel]="cart.prescriptionOnFile()?.id ?? ''"
                  (ngModelChange)="pickPrescription($event)"
                  aria-label="Ordonnance en dossier"
                >
                  <option value="">Ordonnance papier (saisie)</option>
                  @for (p of prescriptionsOnFile(); track p.id) {
                    <option [value]="p.id">
                      {{ p.prescriberName }} · {{ p.renewalsRemaining }} délivrance(s) restante(s) ·
                      valable jusqu'au {{ p.validUntil }}
                    </option>
                  }
                </select>
              }
              <input
                class="input"
                placeholder="Prescripteur"
                [ngModel]="cart.prescription()?.prescriberName"
                (ngModelChange)="updatePrescription({ prescriberName: $event })"
                aria-label="Prescripteur"
              />
              <div class="grid grid-cols-2 gap-2">
                <input
                  class="input"
                  placeholder="RPPS (11 chiffres)"
                  [ngModel]="cart.prescription()?.prescriberRpps"
                  (ngModelChange)="updatePrescription({ prescriberRpps: $event })"
                  aria-label="RPPS"
                />
                <input
                  class="input"
                  placeholder="Réf. patient (pseudonyme)"
                  [ngModel]="cart.prescription()?.patientReference"
                  (ngModelChange)="updatePrescription({ patientReference: $event })"
                  aria-label="Référence patient"
                />
              </div>
            </fieldset>
          }

          <div class="relative">
            @if (cart.customer(); as c) {
              <div
                class="flex items-center gap-3 rounded-[var(--radius)] border bg-surface-2 px-3 py-2"
              >
                <span
                  class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-brand-soft text-brand"
                >
                  <app-icon name="user" [size]="16" />
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-sm font-semibold">{{ c.displayName }}</span>
                  <span class="block text-xs text-muted"
                    >dû {{ c.creditBalanceCents | money }}</span
                  >
                </span>
                <button
                  type="button"
                  class="btn btn-ghost btn-icon btn-sm"
                  (click)="clearCustomer()"
                  aria-label="Retirer le client"
                >
                  <app-icon name="x" [size]="16" />
                </button>
              </div>
            } @else {
              <label class="input-with-icon">
                <app-icon name="user" [size]="16" />
                <input
                  type="search"
                  class="input"
                  placeholder="Client (nom, téléphone) — facultatif"
                  [(ngModel)]="customerTerm"
                  (ngModelChange)="searchCustomers($event)"
                  aria-label="Client"
                />
              </label>
            }
            @if (customerResults().length > 0) {
              <ul
                class="results absolute z-20 mt-2 max-h-56 w-full overflow-auto p-1"
                role="listbox"
              >
                @for (c of customerResults(); track c.id) {
                  <li
                    role="option"
                    class="cursor-pointer rounded-[var(--radius-sm)] px-3 py-2"
                    (mousedown)="pickCustomer(c)"
                  >
                    <span class="block text-sm font-medium">{{ c.displayName }}</span>
                    <span class="block text-xs text-muted"
                      >{{ c.phone }} · crédit dispo {{ c.availableCreditCents | money }}</span
                    >
                  </li>
                }
              </ul>
            }
          </div>

          @if (policies().length > 0) {
            <fieldset class="panel panel-brand space-y-2 text-sm">
              <legend class="panel-title">
                <app-icon name="insurance" [size]="16" />
                Tiers payant
              </legend>
              <select
                class="select"
                [ngModel]="cart.policy()?.id ?? ''"
                (ngModelChange)="pickPolicy($event)"
                aria-label="Police d'assurance"
              >
                <option value="">Sans tiers payant</option>
                @for (p of policies(); track p.id) {
                  <option [value]="p.id">
                    {{ p.insurerName }} · {{ p.coverageRate }} % · {{ p.policyNumber }}
                  </option>
                }
              </select>
              @if (cart.policy(); as policy) {
                <p class="flex justify-between">
                  <span class="text-muted"
                    >Part {{ policy.insurerName }} ({{ policy.coverageRate }} %)</span
                  >
                  <strong class="num">{{ cart.insuredShareCents() | money }}</strong>
                </p>
                @if (policy.remainingCeilingCents !== null) {
                  <p class="text-xs text-subtle">
                    Plafond mensuel restant : {{ policy.remainingCeilingCents | money }}
                  </p>
                }
                <p class="flex justify-between">
                  <span class="text-muted">Reste à charge du patient</span>
                  <strong class="num">{{
                    cart.netCents() - cart.insuredShareCents() | money
                  }}</strong>
                </p>
              }
            </fieldset>
          }

          <div>
            <p class="label">Encaissement</p>
            <div class="grid grid-cols-3 gap-2">
              @for (method of visibleMethods(); track method.code) {
                <button type="button" class="method" (click)="pay(method.code)">
                  <app-icon [name]="method.icon" [size]="20" />
                  <span>{{ method.label }}</span>
                  @if (method.key) {
                    <span class="kbd absolute top-1.5 right-1.5">{{ method.key }}</span>
                  }
                </button>
              }
            </div>
          </div>
          <div class="grid grid-cols-2 gap-2">
            <div>
              <label class="label" for="pos-amount">Montant</label>
              <input
                id="pos-amount"
                type="text"
                inputmode="decimal"
                class="input"
                [(ngModel)]="amount"
                placeholder="Reste {{ cart.remainingCents() | money }}"
              />
            </div>
            <div>
              <label class="label" for="pos-provider">Opérateur mobile money</label>
              <select id="pos-provider" class="select" [(ngModel)]="provider">
                @for (p of providers; track p) {
                  <option [value]="p">{{ p.replace('_', ' ') }}</option>
                }
              </select>
            </div>
          </div>
          @if (!cashSessionOpen()) {
            <div class="alert alert-warning text-xs">
              <app-icon name="alert" [size]="16" />
              <span
                >Aucune session de caisse ouverte sur ce poste : les espèces ne seront pas
                rattachées à un rapport Z.</span
              >
            </div>
          }

          @if (cart.payments().length > 0) {
            <ul class="divide-y rounded-[var(--radius)] border text-sm">
              @for (payment of cart.payments(); track $index) {
                <li class="flex items-center gap-2 px-3 py-2">
                  <app-icon name="checkCircle" [size]="16" class="text-success" />
                  <span class="flex-1"
                    >{{ labelOf(payment.method) }}
                    @if (payment.provider) {
                      <span class="text-muted">· {{ payment.provider }}</span>
                    }
                    @if (payment.policyId) {
                      <span class="text-muted">· {{ cart.policy()?.insurerName }}</span>
                    }
                  </span>
                  <span class="num font-medium">{{ payment.amountCents | money }}</span>
                  <button
                    type="button"
                    class="btn btn-ghost btn-icon btn-sm"
                    (click)="cart.removePayment($index)"
                    aria-label="Supprimer le règlement"
                  >
                    <app-icon name="x" [size]="14" />
                  </button>
                </li>
              }
            </ul>
          }

          @for (issue of cart.blockingIssues(); track issue) {
            <p class="flex items-start gap-2 text-sm text-danger">
              <app-icon name="error" [size]="16" class="mt-0.5" />{{ issue }}
            </p>
          }
          @for (warning of cart.stockWarnings(); track warning) {
            <p class="flex items-start gap-2 text-sm text-warning">
              <app-icon name="alert" [size]="16" class="mt-0.5" />{{ warning }}
            </p>
          }
        </div>

        <div class="space-y-3 border-t bg-surface-2 p-4">
          <p class="flex items-baseline justify-between font-semibold">
            <span>Reste à payer</span>
            <span class="num text-lg"><app-animated-number [value]="cart.remainingCents()" /></span>
          </p>
          @if (cart.changeDueCents() > 0) {
            <p
              class="pop-enter flex items-baseline justify-between rounded-[var(--radius-sm)] bg-[var(--success-soft)] px-3 py-2 font-bold text-success"
            >
              <span>Rendu monnaie</span>
              <span class="num text-xl"
                ><app-animated-number [value]="cart.changeDueCents()" [live]="true"
              /></span>
            </p>
          }
          @if (saleRefusal(); as refusal) {
            <p
              class="flex items-start gap-2 rounded-[var(--radius-sm)] bg-[var(--danger-soft)] px-3 py-2 text-sm font-medium text-[var(--danger-strong)]"
              role="alert"
              data-testid="pos-license-blocked"
            >
              <app-icon name="ban" [size]="16" class="mt-0.5 shrink-0" />{{ refusal }}
            </p>
          }
          <button
            type="button"
            class="btn btn-primary btn-lg w-full"
            [disabled]="!cart.canCheckout() || busy() || license.blocked()"
            (click)="checkout()"
          >
            <app-icon name="check" [size]="20" [stroke]="2.25" />
            Valider la vente
            <span class="kbd ml-1 !border-white/30 !bg-white/15 !text-on-brand">F9</span>
          </button>
          <p
            class="flex items-center justify-center gap-1.5 text-xs text-muted"
            [class.!text-warning]="!connectivity.online()"
          >
            <app-icon [name]="connectivity.online() ? 'wifi' : 'wifiOff'" [size]="14" />
            {{
              connectivity.online()
                ? 'En ligne'
                : 'Hors-ligne : la vente sera synchronisée automatiquement'
            }}
            · {{ outbox.pendingCount() }} en attente
          </p>
          @if (receipt(); as last) {
            <div class="flex flex-wrap gap-2">
              <button
                type="button"
                class="btn btn-secondary btn-sm flex-1"
                (click)="printReceipt(last, false)"
              >
                <app-icon name="printer" [size]="14" />
                Réimprimer le ticket {{ last.ticketNumber }}
              </button>
              @if (printing.settings().invoicePaper === 'A4') {
                <button
                  type="button"
                  class="btn btn-secondary btn-sm"
                  (click)="printReceipt(last, true)"
                >
                  <app-icon name="prescription" [size]="14" />
                  Facture A4
                </button>
              }
            </div>
          }
        </div>
      </aside>
    </div>

    @if (receipt(); as data) {
      <div class="hidden print:block"><app-receipt [data]="data" [format]="printFormat()" /></div>
    }
  `,
  styles: `
    /* Cart line washed with the brand color when added or changed (MotionFeedback "flash"):
       each cell carries the wash behind its content, only its opacity is animated. */
    tr.flash-surface > td {
      position: relative;
      isolation: isolate;
    }
    tr.flash-surface > td::before {
      content: '';
      position: absolute;
      inset: 0;
      z-index: -1;
      background: color-mix(in oklab, var(--brand-primary) 16%, var(--surface));
      opacity: 0;
      pointer-events: none;
    }
    .scan > input {
      height: 3.5rem;
      padding-left: 3.25rem;
      padding-right: 3.5rem;
      font-size: 1.125rem;
      border-width: 2px;
      border-color: color-mix(in oklab, var(--brand-primary) 55%, var(--border));
      border-radius: var(--radius);
      box-shadow: var(--shadow-sm);
    }
    .scan > app-icon {
      left: 1rem;
      color: var(--brand-text);
    }
    .results {
      border: 1px solid var(--border);
      border-radius: var(--radius);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
    }
    .results li:hover,
    .results li[aria-selected='true'] {
      background: var(--brand-soft);
    }
    .total {
      position: relative;
      overflow: hidden;
      background: linear-gradient(180deg, var(--brand-softer), var(--surface));
      border-bottom: 1px solid var(--border);
    }
    .sale-done {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 0 1.25rem;
      background: linear-gradient(
        120deg,
        var(--success-soft),
        color-mix(in oklab, var(--success-soft) 60%, var(--surface))
      );
      color: var(--success);
      cursor: pointer;
      animation: sale-in var(--motion-long) var(--ease-decelerate);
    }
    .sale-done.sale-done-leave {
      animation: sale-out var(--motion-medium) var(--ease-accelerate) forwards;
    }
    .sale-check {
      position: relative;
      flex-shrink: 0;
      width: 52px;
      height: 52px;
    }
    .sale-check svg {
      display: block;
      overflow: visible;
    }
    .sale-check-disc {
      fill: var(--surface);
      transform-box: fill-box;
      transform-origin: center;
      animation: disc-in 420ms var(--ease-emphasized) backwards;
    }
    .sale-check-ring,
    .sale-check-mark {
      fill: none;
      stroke: currentColor;
      stroke-linecap: round;
      stroke-linejoin: round;
      stroke-dasharray: 1;
    }
    .sale-check-ring {
      stroke-width: 2.5;
      transform: rotate(-90deg);
      transform-box: fill-box;
      transform-origin: center;
      animation: draw 420ms var(--ease-decelerate) 60ms backwards;
    }
    .sale-check-mark {
      stroke-width: 3.5;
      animation: draw 260ms var(--ease-emphasized) 300ms backwards;
    }
    .sale-spark {
      position: absolute;
      top: 50%;
      left: 50%;
      width: 5px;
      height: 5px;
      margin: -2.5px;
      border-radius: 999px;
      background: currentColor;
      opacity: 0;
      animation: spark 620ms var(--ease-decelerate) 380ms;
    }
    .sale-spark:nth-of-type(even) {
      background: var(--brand-primary);
    }
    .sale-done-title {
      display: block;
      font-size: 0.75rem;
      font-weight: 650;
      letter-spacing: 0.02em;
      color: color-mix(in oklab, var(--success) 80%, var(--text));
    }
    .sale-done-label {
      display: block;
      margin-top: 0.35rem;
      font-size: 0.75rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      opacity: 0.85;
    }
    .sale-done-amount {
      display: block;
      font-size: 2rem;
      line-height: 1.1;
      font-weight: 750;
      letter-spacing: -0.02em;
      font-variant-numeric: tabular-nums;
      animation: rise-in var(--motion-long) var(--ease-decelerate) 220ms backwards;
    }
    .pop-enter {
      animation: pop-in var(--motion-medium) var(--ease-emphasized);
    }
    @keyframes sale-in {
      from {
        opacity: 0;
        transform: translateY(8px);
      }
    }
    @keyframes sale-out {
      to {
        opacity: 0;
        transform: translateY(-8px);
      }
    }
    @keyframes disc-in {
      from {
        transform: scale(0.4);
      }
    }
    @keyframes spark {
      0% {
        opacity: 1;
        transform: rotate(var(--a)) translateY(-20px) scale(1);
      }
      100% {
        opacity: 0;
        transform: rotate(var(--a)) translateY(-38px) scale(0.4);
      }
    }
    .panel {
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: 0.75rem;
    }
    .panel-brand {
      border-color: color-mix(in oklab, var(--brand-primary) 30%, var(--border));
      background: var(--brand-softer);
    }
    .panel-title {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0 0.35rem;
      font-size: 0.8125rem;
      font-weight: 650;
    }
    .method {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.35rem;
      min-height: calc(4.5rem * var(--density));
      padding: 0.5rem;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
      font-size: 0.8125rem;
      font-weight: 600;
      line-height: 1.15;
      text-align: center;
      transition:
        border-color 120ms ease,
        background-color 120ms ease,
        transform 80ms ease;
    }
    .method > app-icon {
      color: var(--brand-text);
    }
    .method:hover {
      border-color: var(--brand-primary);
      background: var(--brand-softer);
    }
    .method:active {
      transform: scale(0.97);
    }
    .stepper {
      display: inline-flex;
      align-items: center;
      height: 2.25rem;
      border: 1px solid var(--border-strong);
      border-radius: var(--radius-sm);
      background: var(--surface);
      overflow: hidden;
    }
    .stepper > button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2rem;
      height: 100%;
      color: var(--text-muted);
    }
    .stepper > button:hover {
      background: var(--surface-3);
      color: var(--text);
    }
    .stepper > input {
      width: 3rem;
      height: 100%;
      border: 0;
      border-radius: 0;
      padding: 0;
      text-align: center;
      font-weight: 600;
      background: transparent;
      box-shadow: none;
      -moz-appearance: textfield;
    }
    .stepper > input::-webkit-inner-spin-button {
      -webkit-appearance: none;
    }
  `,
})
export class PosPage {
  protected readonly cart = inject(CartStore);
  protected readonly connectivity = inject(ConnectivityService);
  protected readonly outbox = inject(SaleOutboxService);
  protected readonly license = inject(LicenseStateService);
  protected readonly saleRefusal = computed(() => saleRefusal(this.license.state()));
  private readonly catalog = inject(CatalogSyncService);
  private readonly terminal = inject(TerminalService);
  private readonly permissions = inject(PermissionService);
  private readonly databases = inject(PosDatabaseService);
  private readonly searchInput = viewChild.required<ElementRef<HTMLInputElement>>('search');

  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly currency = inject(CurrencyService);
  private readonly toasts = inject(ToastService);
  private readonly money = new MoneyPipe();

  protected term = '';
  protected amount = '';
  protected customerTerm = '';
  protected provider: string = MOBILE_MONEY_PROVIDERS[0];
  protected readonly providers = MOBILE_MONEY_PROVIDERS;
  protected readonly customerResults = signal<Customer[]>([]);
  /** Covers and renewable prescriptions of the selected customer. */
  protected readonly policies = signal<InsurancePolicy[]>([]);
  protected readonly prescriptionsOnFile = signal<Prescription[]>([]);
  protected readonly cashSessionOpen = signal(true);
  protected readonly results = signal<CachedProduct[]>([]);
  protected readonly placeOf = placeOf;
  protected readonly highlighted = signal(0);
  protected readonly message = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly receipt = signal<ReceiptData | null>(null);
  protected readonly printing = inject(PrintSettingsService);
  protected readonly printFormat = signal<PaperFormat>('MM80');
  protected readonly methods: {
    code: PaymentMethod;
    label: string;
    icon: IconName;
    key?: string;
  }[] = [
    { code: 'CASH', label: 'Espèces', icon: 'cash', key: 'F4' },
    { code: 'MOBILE_MONEY', label: 'Mobile money', icon: 'mobileMoney', key: 'F5' },
    { code: 'CARD', label: 'Carte', icon: 'card' },
    { code: 'THIRD_PARTY_PAYER', label: 'Tiers payant', icon: 'insurance' },
    { code: 'CREDIT', label: 'Crédit client', icon: 'credit' },
    { code: 'CHECK', label: 'Chèque', icon: 'receipt' },
  ];
  /** The insured share is computed from the policy, never typed: its button hides once a policy applies. */
  protected readonly visibleMethods = computed(() =>
    this.methods.filter((m) => m.code !== 'THIRD_PARTY_PAYER' || this.policies().length === 0),
  );
  private readonly cashierName = computed(() => this.permissions.profile()?.displayName ?? '');
  private readonly idle = inject(IdleService);
  private readonly sound = inject(SoundService);
  /** Success banner of the sale just recorded, over the total. */
  protected readonly saleDone = signal<SaleDone | null>(null);
  protected readonly saleBanner = withExits(
    () => {
      const done = this.saleDone();
      return done ? [done] : [];
    },
    (done) => done.ticketNumber,
  );
  /** Cart lines as displayed: a removed line plays its exit before it goes. */
  protected readonly lines = withExits(
    () => this.cart.lines(),
    (line) => line.productId,
  );
  protected readonly burst = BURST;
  private saleDoneTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.saleDoneTimer));
    void this.checkCashSession();
    // Another user switched in from the lock screen: the basket stays at the counter, but the
    // amounts the previous cashier typed are dropped and taken again by whoever cashes the sale.
    let cashier = this.permissions.profile()?.userId;
    effect(() => {
      const current = this.permissions.profile()?.userId;
      if (current && cashier && current !== cashier) {
        untracked(() => {
          if (this.cart.payments().length > 0) {
            this.cart.payments.set([]);
            this.amount = '';
            this.message.set(
              `Panier conservé pour ${this.cashierName()} : ressaisissez les règlements`,
            );
          }
        });
      }
      cashier = current ?? cashier;
    });
  }

  private async loadCustomerFiles(customer: Customer): Promise<void> {
    try {
      const [policies, prescriptions] = await Promise.all([
        firstValueFrom(
          this.http.get<InsurancePolicy[]>(
            `${this.env.apiUrl}/v1/customers/${customer.id}/policies`,
          ),
        ),
        firstValueFrom(
          this.http.get<Prescription[]>(`${this.env.apiUrl}/v1/prescriptions`, {
            params: { customerId: customer.id, status: 'ACTIVE', limit: 20 },
          }),
        ),
      ]);
      this.policies.set(policies);
      this.prescriptionsOnFile.set(prescriptions.filter((p) => p.renewalsRemaining > 0));
      if (policies.length === 1) {
        this.cart.applyPolicy(policies[0]);
      }
    } catch {
      // Offline: the covers cannot be checked, the sale goes on without third-party payer.
      this.policies.set([]);
      this.prescriptionsOnFile.set([]);
    }
  }

  protected pickPolicy(policyId: string): void {
    const policy = this.policies().find((p) => p.id === policyId) ?? null;
    if (policy) {
      this.cart.applyPolicy(policy);
    } else {
      this.cart.policy.set(null);
      this.cart.payments.update((payments) =>
        payments.filter((p) => !(p.method === 'THIRD_PARTY_PAYER' && p.policyId)),
      );
    }
  }

  protected pickPrescription(prescriptionId: string): void {
    this.cart.usePrescriptionOnFile(
      this.prescriptionsOnFile().find((p) => p.id === prescriptionId) ?? null,
    );
  }

  protected clearCustomer(): void {
    this.cart.customer.set(null);
    this.policies.set([]);
    this.prescriptionsOnFile.set([]);
    this.pickPolicy('');
    this.cart.usePrescriptionOnFile(null);
  }

  private async checkCashSession(): Promise<void> {
    try {
      const response = await firstValueFrom(
        this.http.get(`${this.env.apiUrl}/v1/cash/sessions/open`, {
          params: { terminalId: this.terminal.terminalId },
          observe: 'response',
        }),
      );
      this.cashSessionOpen.set(response.status === 200);
    } catch {
      // Offline: cannot tell, do not nag.
    }
  }

  protected async searchCustomers(term: string): Promise<void> {
    if (term.trim().length < 2) {
      this.customerResults.set([]);
      return;
    }
    try {
      this.customerResults.set(
        await firstValueFrom(
          this.http.get<Customer[]>(`${this.env.apiUrl}/v1/customers`, {
            params: { q: term.trim(), limit: 8 },
          }),
        ),
      );
    } catch {
      this.customerResults.set([]);
    }
  }

  protected pickCustomer(customer: Customer): void {
    this.cart.customer.set(customer);
    this.customerTerm = '';
    this.customerResults.set([]);
    void this.loadCustomerFiles(customer);
  }

  protected async lookup(term: string): Promise<void> {
    this.highlighted.set(0);
    this.results.set(
      term.trim().length >= 2 && !/^\d+$/.test(term) ? await this.catalog.search(term) : [],
    );
  }

  /** Replaces the result list with the substitutes of an out-of-stock product (same DCI). */
  protected async showAlternatives(product: CachedProduct): Promise<void> {
    const substitutes = await this.catalog.alternatives(product);
    this.highlighted.set(0);
    this.results.set(substitutes);
    this.message.set(
      substitutes.length === 0
        ? `Aucun équivalent de ${product.designation} (${product.dci}) au catalogue`
        : `${substitutes.length} équivalent(s) de ${product.designation} — même DCI ${product.dci}`,
    );
  }

  protected async scan(): Promise<void> {
    // A GS1 DataMatrix (boxes with lot and expiry) is looked up by the EAN it carries
    const term = parseGs1(this.term.trim())?.ean13 ?? this.term.trim();
    if (!term) {
      return;
    }
    const candidates = this.results().length > 0 ? this.results() : await this.catalog.search(term);
    const product = candidates[this.highlighted()] ?? candidates[0];
    if (product) {
      this.pick(product);
    } else {
      this.message.set(`Aucun produit pour « ${term} »`);
      this.sound.play('refusal');
    }
  }

  protected pick(product: CachedProduct): void {
    try {
      this.cart.add(product);
      this.message.set(null);
      this.saleDone.set(null);
      this.sound.play('scan');
    } catch (error) {
      this.message.set((error as Error).message);
      this.sound.play('refusal');
    }
    this.term = '';
    this.results.set([]);
    this.searchInput().nativeElement.focus();
  }

  protected move(delta: number): void {
    const count = this.results().length;
    if (count > 0) {
      this.highlighted.update((index) => (index + delta + count) % count);
    }
  }

  protected percent(line: {
    unitPriceCents: number;
    quantity: number;
    discountCents: number;
  }): number {
    return discountPercent(line);
  }

  protected updatePrescription(
    change: Partial<{ prescriberName: string; prescriberRpps: string; patientReference: string }>,
  ): void {
    this.cart.prescription.update((current) => ({ prescriberName: '', ...current, ...change }));
  }

  protected pay(method: PaymentMethod): void {
    const policy = this.cart.policy();
    if (method === 'THIRD_PARTY_PAYER' && policy) {
      this.cart.applyPolicy(policy);
      return;
    }
    const typed = this.currency.parse(this.amount);
    const cents = typed > 0 ? typed : this.cart.remainingCents();
    this.cart.addPayment({
      method,
      amountCents: cents,
      thirdPartyCode: method === 'THIRD_PARTY_PAYER' ? 'AMO' : undefined,
      provider: method === 'MOBILE_MONEY' ? this.provider : undefined,
    });
    this.amount = '';
  }

  protected labelOf(method: PaymentMethod): string {
    return this.methods.find((m) => m.code === method)?.label ?? method;
  }

  protected onShortcut(event: KeyboardEvent): void {
    switch (event.key) {
      case 'F2':
        event.preventDefault();
        this.searchInput().nativeElement.focus();
        break;
      case 'F4':
        event.preventDefault();
        this.pay('CASH');
        break;
      case 'F5':
        event.preventDefault();
        this.pay('MOBILE_MONEY');
        break;
      case 'F9':
        event.preventDefault();
        void this.checkout();
        break;
      case 'Escape':
        if (!this.term) {
          this.cart.clear();
        }
        break;
    }
  }

  /**
   * Ticket on the register's thermal printer through the local server when one is configured
   * (the sale may not be synchronized yet, so the terminal sends the ticket itself); otherwise,
   * or when the printer cannot be reached, the browser prints the HTML ticket at the configured
   * paper size. An invoice always goes through the browser on A4.
   */
  protected async printReceipt(data: ReceiptData, invoice: boolean): Promise<void> {
    const settings = this.printing.settings();
    if (invoice) {
      this.printFormat.set('A4');
      this.printing.printPage('A4');
      return;
    }
    if (settings.mode !== 'BROWSER') {
      const ticket: TerminalTicketRequest = {
        ticketNumber: data.ticketNumber,
        occurredAt: data.occurredAt.toISOString(),
        cashier: data.cashier,
        customer: data.customer ?? null,
        lines: data.lines.map((l) => ({
          designation: l.designation,
          quantity: l.quantity,
          unitPriceCents: l.unitPriceCents,
          discountCents: l.discountCents,
          vatRate: l.vatRate,
        })),
        discountCents: data.discountCents,
        netCents: data.netCents,
        payments: data.payments.map((p) => ({
          label: `${PAYMENT_LABELS[p.method] ?? p.method}${p.provider ? ' ' + p.provider.replace(/_/g, ' ').toLowerCase() : ''}`,
          amountCents: p.amountCents,
        })),
        changeDueCents: data.changeDueCents,
        vat: data.vat.map((v) => ({ rate: v.rate, totalTtcCents: v.totalTtc, vatCents: v.vat })),
        offline: data.offline,
      };
      if (await this.printing.printTicket(ticket)) {
        return;
      }
      this.message.set('Imprimante thermique injoignable : impression par le navigateur');
    }
    const paper = settings.paper === 'A4' ? 'A4' : settings.paper;
    this.printFormat.set(paper);
    this.printing.printPage(paper);
  }

  /**
   * Success banner over the total: drawn check, change due in large. It never takes the focus
   * (the scan field keeps it) and leaves on the next scan; the toast carries the announcement.
   */
  private celebrate(done: SaleDone): void {
    clearTimeout(this.saleDoneTimer);
    this.saleDone.set(done);
    this.sound.play('sale');
    this.saleDoneTimer = setTimeout(
      () => this.saleDone.set(null),
      done.changeDueCents > 0 ? SALE_DONE_WITH_CHANGE_MS : SALE_DONE_MS,
    );
  }

  /** Commits the sale locally first (zero data loss), prints, then lets the outbox sync it. */
  protected async checkout(): Promise<void> {
    if (!this.cart.canCheckout() || this.busy()) {
      return;
    }
    // Enforced offline too, from the state cached on this terminal; the server refuses anyway
    const refusal = this.saleRefusal();
    if (refusal) {
      this.message.set(refusal);
      return;
    }
    this.busy.set(true);
    const release = this.idle.hold();
    try {
      const profile = this.permissions.profile();
      if (!profile) {
        throw new Error('Profil utilisateur indisponible : reconnectez-vous');
      }
      const db = this.databases.db();
      const occurredAt = new Date();
      const applied = this.cart.appliedPayments();
      const prescription = this.cart.prescription();
      let ticketNumber = '';
      await db.transaction('rw', db.meta, db.outbox, db.products, async () => {
        ticketNumber = await this.terminal.nextTicketNumber();
        await this.outbox.enqueue({
          clientSaleId: uuidv7(occurredAt.getTime()),
          terminalId: this.terminal.terminalId,
          ticketNumber,
          occurredAt: occurredAt.toISOString(),
          cashierUserId: profile.userId,
          customerId: this.cart.customer()?.id,
          lines: this.cart.lines().map((l) => ({
            productId: l.productId,
            quantity: l.quantity,
            unitPriceCents: l.unitPriceCents,
            discountCents: l.discountCents,
          })),
          payments: applied.map((p) => ({
            method: p.method,
            amountCents: p.amountCents,
            thirdPartyCode: p.thirdPartyCode,
            provider: p.provider,
            policyId: p.policyId,
          })),
          prescription: this.cart.requiresPrescription() && prescription ? prescription : undefined,
        });
      });
      const customer = this.cart.customer();
      const policy = this.cart.policy();
      const data: ReceiptData = {
        ticketNumber,
        terminalId: this.terminal.terminalId,
        occurredAt,
        cashier: this.cashierName(),
        customer: customer
          ? {
              displayName: customer.displayName,
              phone: customer.phone,
              address: customer.address,
              policy: policy ? `${policy.insurerName} ${policy.policyNumber}` : null,
            }
          : null,
        lines: this.cart.lines(),
        payments: this.cart.payments(),
        grossCents: this.cart.grossCents(),
        discountCents: this.cart.discountCents(),
        netCents: this.cart.netCents(),
        changeDueCents: this.cart.changeDueCents(),
        vat: this.cart.vatBreakdown(),
        offline: !this.connectivity.online(),
      };
      this.receipt.set(data);
      this.cart.clear();
      this.celebrate({
        ticketNumber,
        netCents: data.netCents,
        changeDueCents: data.changeDueCents,
      });
      this.toasts.success(
        data.changeDueCents > 0
          ? `Vente ${ticketNumber} enregistrée · rendu ${this.money.transform(data.changeDueCents)}`
          : `Vente ${ticketNumber} enregistrée`,
      );
      if (this.printing.settings().autoPrint) {
        void this.printReceipt(data, false);
      }
      void this.outbox.flush();
    } catch (error) {
      this.message.set(`Vente non enregistrée : ${(error as Error).message}`);
    } finally {
      release();
      this.busy.set(false);
      this.searchInput().nativeElement.focus();
    }
  }
}
