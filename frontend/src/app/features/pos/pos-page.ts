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
import { DatePipe } from '@angular/common';
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
import { SafetyService } from '../../core/safety/safety.service';
import { PosologyLabels } from './posology-labels';
import { PaymentNotificationsService } from '../../core/payments/payment-notifications.service';
import { PaymentNotification } from '../../core/api/models';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  POSOLOGY_MOMENTS,
  Posology,
  PosologyMoment,
  emptyPosology,
  normalizePosology,
  posologySummary,
} from '../../shared/posology';
import { CartLine, CartPayment } from './cart.store';
import { SAFETY_SEVERITY_LABELS, SafetyAlert } from '../../core/api/models';
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
    DatePipe,
    FormsModule,
    MoneyPipe,
    Receipt,
    Icon,
    LocationChip,
    ProductThumb,
    AnimatedNumber,
    EmptyState,
    MotionFeedback,
    PosologyLabels,
  ],
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
                  @if (product.availableQuantity === 0 && cart.customer()) {
                    <button
                      type="button"
                      class="btn btn-soft btn-sm"
                      (mousedown)="$event.stopPropagation(); requestWish(product)"
                      title="Prévenir le client par WhatsApp ou SMS quand le produit revient"
                    >
                      <app-icon name="bellRing" [size]="14" />
                      Prévenir à l'arrivée
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

        @if (cart.safetyAlerts().length > 0 || safetyPending()) {
          <section class="card overflow-hidden" data-testid="pos-safety" aria-live="polite">
            <div class="card-header">
              <div class="flex items-center gap-2">
                <app-icon
                  name="shield"
                  [size]="18"
                  [class.text-danger]="cart.blockingSafety().length > 0"
                  [class.text-warning]="cart.blockingSafety().length === 0"
                />
                <h2 class="card-title">Vigilance</h2>
                @if (cart.safetyAlerts().length > 0) {
                  <span
                    class="badge"
                    [class.badge-danger]="cart.blockingSafety().length > 0"
                    [class.badge-warning]="cart.blockingSafety().length === 0"
                    >{{ cart.safetyAlerts().length }} signalement(s)</span
                  >
                }
              </div>
              <span class="text-xs text-subtle">
                @if (safetyPending()) {
                  Vérification…
                } @else if (!safetyServerChecked()) {
                  Serveur injoignable : profil du client non vérifié
                } @else if (cart.customer() && !safetyProfileKnown()) {
                  Aucun profil santé pour ce client
                } @else if (cart.customer()) {
                  Profil santé du client vérifié
                }
              </span>
            </div>
            <ul class="divide-y">
              @for (alert of cart.safetyAlerts(); track alert.key) {
                <li
                  class="flex items-start gap-3 px-4 py-2.5 text-sm"
                  [style.background-color]="
                    alert.severity === 'CONTRAINDICATED' && !cart.safetyOverrides().has(alert.key)
                      ? 'var(--danger-soft)'
                      : null
                  "
                >
                  <app-icon
                    [name]="alert.severity === 'PRECAUTION' ? 'info' : 'alert'"
                    [size]="18"
                    class="mt-0.5 shrink-0"
                    [class.text-danger]="alert.severity === 'CONTRAINDICATED'"
                    [class.text-warning]="alert.severity === 'ADVISED_AGAINST'"
                    [class.text-muted]="alert.severity === 'PRECAUTION'"
                  />
                  <div class="min-w-0 flex-1">
                    <p class="flex flex-wrap items-center gap-2 font-medium">
                      <span
                        class="badge"
                        [class.badge-danger]="alert.severity === 'CONTRAINDICATED'"
                        [class.badge-warning]="alert.severity === 'ADVISED_AGAINST'"
                        >{{ severityLabel(alert) }}</span
                      >
                      {{ alert.title }}
                    </p>
                    @if (alert.detail) {
                      <p class="mt-0.5 text-xs text-muted">{{ alert.detail }}</p>
                    }
                    @if (cart.safetyOverrides().get(alert.key); as reason) {
                      <p class="mt-0.5 text-xs font-medium text-success">
                        Levée par le pharmacien : {{ reason }}
                      </p>
                    }
                  </div>
                  @if (
                    alert.severity === 'CONTRAINDICATED' &&
                    !cart.safetyOverrides().has(alert.key) &&
                    canOverride()
                  ) {
                    <button
                      type="button"
                      class="btn btn-secondary btn-sm shrink-0"
                      (click)="overriding.set(alert)"
                    >
                      Passer outre
                    </button>
                  }
                </li>
              }
            </ul>
          </section>
        }

        @if (posologyEditing(); as editing) {
          <div
            class="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(2_6_12/0.5)] p-0 backdrop-blur-sm sm:items-center sm:p-4"
            role="dialog"
            aria-modal="true"
            aria-label="Posologie"
          >
            <form
              class="card w-full max-w-lg rounded-b-none shadow-[var(--shadow-lg)] animate-in sm:rounded-b-[var(--radius-lg)]"
              (ngSubmit)="savePosology(editing.productId)"
            >
              <div class="card-header">
                <div class="min-w-0">
                  <h3 class="card-title">Posologie</h3>
                  <p class="mt-0.5 truncate text-sm text-muted">{{ editing.designation }}</p>
                </div>
                <app-icon name="pill" [size]="20" class="shrink-0 text-brand" />
              </div>
              <div class="card-body space-y-4">
                <div class="flex flex-wrap gap-1.5">
                  @for (preset of presets; track preset.label) {
                    <button
                      type="button"
                      class="btn btn-soft btn-sm"
                      (click)="applyPreset(preset.posology)"
                    >
                      {{ preset.label }}
                    </button>
                  }
                </div>
                <div class="grid grid-cols-4 gap-2">
                  @for (moment of moments; track moment.key) {
                    <div
                      class="rounded-[var(--radius)] border p-2 text-center"
                      [class.border-brand]="posologyDraft[moment.key] > 0"
                      [class.bg-brand-soft]="posologyDraft[moment.key] > 0"
                    >
                      <app-icon [name]="moment.icon" [size]="22" class="mx-auto block text-brand" />
                      <p class="mt-1 text-xs font-medium">{{ moment.label }}</p>
                      <div class="stepper mt-1 justify-center">
                        <button
                          type="button"
                          (click)="stepPosology(moment.key, -1)"
                          [attr.aria-label]="'Moins le ' + moment.label"
                        >
                          <app-icon name="minus" [size]="14" />
                        </button>
                        <input
                          type="number"
                          min="0"
                          max="20"
                          [(ngModel)]="posologyDraft[moment.key]"
                          [name]="'posology-' + moment.key"
                          [attr.aria-label]="moment.label"
                        />
                        <button
                          type="button"
                          (click)="stepPosology(moment.key, 1)"
                          [attr.aria-label]="'Plus le ' + moment.label"
                        >
                          <app-icon name="plus" [size]="14" />
                        </button>
                      </div>
                    </div>
                  }
                </div>
                <div class="grid grid-cols-2 gap-3">
                  <label class="block">
                    <span class="label">Durée (jours)</span>
                    <input
                      type="number"
                      min="0"
                      max="365"
                      class="input"
                      [(ngModel)]="posologyDraft.durationDays"
                      name="posology-duration"
                    />
                  </label>
                  <label class="flex items-end gap-2 pb-2">
                    <input
                      type="checkbox"
                      [(ngModel)]="posologyDraft.withFood"
                      name="posology-food"
                    />
                    Pendant les repas
                  </label>
                </div>
                <label class="block">
                  <span class="label">Consigne (facultative)</span>
                  <input
                    class="input"
                    maxlength="120"
                    placeholder="Ex. : avec un grand verre d'eau, ne pas croquer"
                    [(ngModel)]="posologyDraft.instructions"
                    name="posology-instructions"
                  />
                </label>
                <p class="text-sm text-muted">
                  Sur le ticket et l'étiquette :
                  <strong class="text-ink">{{ summaryOf(posologyDraft) || '—' }}</strong>
                </p>
              </div>
              <div class="flex justify-between gap-2 border-t p-4">
                <button
                  type="button"
                  class="btn btn-ghost"
                  (click)="clearPosology(editing.productId)"
                >
                  Effacer
                </button>
                <div class="flex gap-2">
                  <button
                    type="button"
                    class="btn btn-secondary"
                    (click)="posologyEditing.set(null)"
                  >
                    Annuler
                  </button>
                  <button type="submit" class="btn btn-primary">
                    <app-icon name="check" [size]="16" />
                    Enregistrer
                  </button>
                </div>
              </div>
            </form>
          </div>
        }

        @if (smsEntry()) {
          <div
            class="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(2_6_12/0.5)] p-0 backdrop-blur-sm sm:items-center sm:p-4"
            role="dialog"
            aria-modal="true"
            aria-label="Confirmation de paiement reçue"
          >
            <form
              class="card w-full max-w-md rounded-b-none shadow-[var(--shadow-lg)] animate-in sm:rounded-b-[var(--radius-lg)]"
              (ngSubmit)="recordSms()"
            >
              <div class="card-header">
                <div class="min-w-0">
                  <h3 class="card-title">Confirmation reçue sur le téléphone</h3>
                  <p class="mt-0.5 text-sm text-muted">
                    Recopiez le SMS de l'opérateur : la confirmation est partagée avec tous les
                    postes, même sans Internet, et rattachée au règlement.
                  </p>
                </div>
                <app-icon name="mobileMoney" [size]="20" class="shrink-0 text-brand" />
              </div>
              <div class="card-body space-y-3">
                <div class="grid grid-cols-2 gap-3">
                  <label class="block">
                    <span class="label">Opérateur</span>
                    <select class="select" [(ngModel)]="smsForm.provider" name="sms-provider">
                      @for (p of providers; track p) {
                        <option [value]="p">{{ p.replace('_', ' ') }}</option>
                      }
                    </select>
                  </label>
                  <label class="block">
                    <span class="label">Montant</span>
                    <input
                      class="input text-right tabular-nums"
                      inputmode="decimal"
                      [(ngModel)]="smsForm.amount"
                      name="sms-amount"
                      required
                    />
                  </label>
                </div>
                <label class="block">
                  <span class="label">Référence (Trans ID, Ref)</span>
                  <input
                    class="input"
                    [(ngModel)]="smsForm.reference"
                    name="sms-reference"
                    maxlength="64"
                  />
                </label>
                <label class="block">
                  <span class="label">Texte du SMS (facultatif)</span>
                  <textarea
                    class="textarea"
                    rows="2"
                    maxlength="1000"
                    [(ngModel)]="smsForm.rawText"
                    name="sms-text"
                    (ngModelChange)="readSmsText($event)"
                  ></textarea>
                </label>
              </div>
              <div class="flex justify-end gap-2 border-t p-4">
                <button type="button" class="btn btn-ghost" (click)="smsEntry.set(false)">
                  Annuler
                </button>
                <button type="submit" class="btn btn-primary" [disabled]="!smsForm.amount">
                  <app-icon name="check" [size]="16" />
                  Enregistrer la confirmation
                </button>
              </div>
            </form>
          </div>
        }

        @if (overriding(); as alert) {
          <div
            class="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(2_6_12/0.5)] p-0 backdrop-blur-sm sm:items-center sm:p-4"
            role="dialog"
            aria-modal="true"
            aria-label="Passer outre une contre-indication"
          >
            <form
              class="card w-full max-w-md rounded-b-none shadow-[var(--shadow-lg)] animate-in sm:rounded-b-[var(--radius-lg)]"
              (ngSubmit)="confirmOverride(alert)"
            >
              <div class="card-header">
                <div class="min-w-0">
                  <h3 class="card-title">Passer outre la contre-indication</h3>
                  <p class="mt-0.5 text-sm text-muted">{{ alert.title }}</p>
                </div>
                <app-icon name="shield" [size]="20" class="shrink-0 text-danger" />
              </div>
              <div class="card-body space-y-3">
                @if (alert.detail) {
                  <p class="text-sm">{{ alert.detail }}</p>
                }
                <p class="text-xs text-muted">
                  Votre décision est enregistrée avec la vente, auditée et signalée au titulaire.
                </p>
                <label class="block">
                  <span class="label">Motif (obligatoire)</span>
                  <textarea
                    class="textarea"
                    rows="2"
                    name="overrideReason"
                    [(ngModel)]="overrideReason"
                    placeholder="Ex. : allergie infirmée par le patient, décision du prescripteur"
                    maxlength="255"
                    required
                    autofocus
                  ></textarea>
                </label>
              </div>
              <div class="flex justify-end gap-2 border-t p-4">
                <button type="button" class="btn btn-ghost" (click)="overriding.set(null)">
                  Annuler
                </button>
                <button type="submit" class="btn btn-primary" [disabled]="!overrideReason.trim()">
                  <app-icon name="check" [size]="16" />
                  Délivrer sous ma responsabilité
                </button>
              </div>
            </form>
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
                          <button
                            type="button"
                            class="badge cursor-pointer"
                            [class.badge-brand]="!!line.posology"
                            (click)="editPosology(line)"
                            [attr.aria-label]="'Posologie de ' + line.designation"
                          >
                            <app-icon name="pill" [size]="12" />
                            {{ line.posology ? summaryOf(line.posology) : 'Posologie' }}
                          </button>
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
          <div class="flex items-end gap-2">
            <label class="block min-w-0 flex-1">
              <span class="label">Référence de la transaction (SMS)</span>
              <input
                class="input"
                [(ngModel)]="reference"
                name="pos-reference"
                maxlength="64"
                placeholder="Facultatif : Trans ID, Ref…"
                autocomplete="off"
              />
            </label>
            <button
              type="button"
              class="btn btn-secondary"
              (click)="smsEntry.set(true)"
              title="Saisir une confirmation reçue sur le téléphone de la pharmacie"
            >
              <app-icon name="mobileMoney" [size]="16" />
              SMS reçu
              @if (paymentNotifications.queued() > 0) {
                <span class="badge badge-warning ml-1">{{ paymentNotifications.queued() }}</span>
              }
            </button>
          </div>

          @if (openConfirmations().length > 0) {
            <div
              class="rounded-[var(--radius)] border bg-[var(--success-soft)] p-3 text-sm"
              data-testid="pos-confirmations"
            >
              <p class="flex items-center gap-2 font-medium text-success">
                <app-icon name="mobileMoney" [size]="16" />
                {{ openConfirmations().length }} confirmation(s) reçue(s), non rattachée(s)
              </p>
              <ul class="mt-1 space-y-0.5 text-xs">
                @for (n of openConfirmations(); track n.id) {
                  <li class="flex flex-wrap items-center gap-x-2">
                    <span class="font-semibold">{{ n.amountCents ?? 0 | money }}</span>
                    <span class="text-muted">{{
                      (n.provider ?? 'opérateur ?').replace('_', ' ')
                    }}</span>
                    @if (n.reference) {
                      <span class="font-mono">{{ n.reference }}</span>
                    }
                    <span class="text-subtle">{{ n.receivedAt | date: 'HH:mm' }}</span>
                  </li>
                }
              </ul>
            </div>
          }

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
                    @if (payment.reference) {
                      <span class="font-mono text-xs text-muted">· {{ payment.reference }}</span>
                    }
                    @if (payment.notificationId) {
                      <span class="badge badge-success ml-1">confirmé</span>
                    } @else if (payment.method === 'MOBILE_MONEY') {
                      @for (n of candidatesFor(payment); track n.id) {
                        <button
                          type="button"
                          class="btn btn-soft btn-sm ml-1"
                          (click)="attachConfirmation($index, n)"
                        >
                          <app-icon name="check" [size]="12" />
                          Confirmation {{ n.reference ?? (n.receivedAt | date: 'HH:mm') }}
                        </button>
                      }
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
              @if (labelCount(last) > 0) {
                <button
                  type="button"
                  class="btn btn-secondary btn-sm"
                  (click)="printLabels(last)"
                  data-testid="pos-print-labels"
                >
                  <app-icon name="pill" [size]="14" />
                  {{ labelCount(last) }} étiquette(s) de posologie
                </button>
              }
            </div>
          }
        </div>
      </aside>
    </div>

    @if (receipt(); as data) {
      <div class="hidden print:block">
        @if (printTarget() === 'labels') {
          <app-posology-labels
            [lines]="data.lines"
            [patient]="data.customer?.displayName ?? null"
            [ticketNumber]="data.ticketNumber"
            [occurredAt]="data.occurredAt"
            [format]="printFormat() === 'MM80' ? 'MM80' : 'MM58'"
          />
        } @else {
          <app-receipt [data]="data" [format]="printFormat()" />
        }
      </div>
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
  private readonly safety = inject(SafetyService);
  protected readonly safetyPending = signal(false);
  protected readonly safetyServerChecked = signal(true);
  protected readonly safetyProfileKnown = signal(false);
  protected readonly overriding = signal<SafetyAlert | null>(null);
  protected readonly paymentNotifications = inject(PaymentNotificationsService);
  protected reference = '';
  protected readonly smsEntry = signal(false);
  protected smsForm = {
    provider: MOBILE_MONEY_PROVIDERS[0] as string,
    amount: '',
    reference: '',
    rawText: '',
  };
  /** Received confirmations not attached to any payment of this basket. */
  protected readonly openConfirmations = computed(() => {
    const attached = new Set(this.cart.payments().map((p) => p.notificationId));
    return this.paymentNotifications.open().filter((n) => !attached.has(n.id));
  });
  protected readonly printTarget = signal<'receipt' | 'labels'>('receipt');
  protected readonly posologyEditing = signal<{ productId: string; designation: string } | null>(
    null,
  );
  protected posologyDraft: Posology = emptyPosology();
  protected readonly moments = POSOLOGY_MOMENTS;
  protected readonly presets: { label: string; posology: Partial<Posology> }[] = [
    { label: '1 matin · 1 soir · 7 j', posology: { morning: 1, evening: 1, durationDays: 7 } },
    {
      label: '1 matin · 1 midi · 1 soir · 5 j',
      posology: { morning: 1, noon: 1, evening: 1, durationDays: 5 },
    },
    { label: '1 matin · 30 j', posology: { morning: 1, durationDays: 30 } },
    { label: '1 coucher · 10 j', posology: { bedtime: 1, durationDays: 10 } },
  ];
  protected overrideReason = '';
  protected readonly canOverride = computed(() => this.permissions.has('SAFETY_OVERRIDE'));
  private safetyTimer: ReturnType<typeof setTimeout> | undefined;
  private safetyRun = 0;
  private safetyKey = '';
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
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.saleDoneTimer);
      clearTimeout(this.safetyTimer);
    });
    this.paymentNotifications.start();
    this.paymentNotifications.arrivals.pipe(takeUntilDestroyed()).subscribe((n) => {
      if (n.status === 'UNMATCHED' && n.incoming) {
        this.sound.play('scan');
        this.toasts.info(
          `Paiement reçu : ${this.money.transform(n.amountCents ?? 0)} ${(n.provider ?? '').replace('_', ' ')}${n.reference ? ' · ' + n.reference : ''}`,
        );
        this.autoAttach(n);
      }
    });
    // Counter vigilance: every change of the basket or of the customer is checked, shortly
    // debounced so a scanner burst costs one request
    effect(() => {
      const items = this.cart.lines().map((l) => ({
        productId: l.productId,
        designation: l.designation,
        dci: l.dci ?? null,
        atcCode: l.atcCode ?? null,
      }));
      const customerId = this.cart.customer()?.id ?? null;
      untracked(() => this.scheduleSafetyCheck(customerId, items));
    });
    void this.checkCashSession();
    // The basket outlives the page (removed while the terminal is locked): the covers and
    // prescriptions of its customer are read again, the chosen cover kept as it was
    const customer = this.cart.customer();
    if (customer) {
      void this.loadCustomerFiles(customer, false);
    }
    // Another user switched in from the lock screen: the basket stays at the counter, but the
    // amounts the previous cashier typed are dropped and taken again by whoever cashes the sale.
    effect(() => {
      const notice = this.cart.handover();
      if (notice) {
        untracked(() => {
          this.amount = '';
          this.message.set(notice);
          this.cart.handover.set(null);
        });
      }
    });
  }

  private async loadCustomerFiles(customer: Customer, applySingleCover = true): Promise<void> {
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
      if (policies.length === 1 && applySingleCover) {
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

  /** The customer wants to be told when the product is back: the delivery will queue the message. */
  protected async requestWish(product: CachedProduct): Promise<void> {
    const customer = this.cart.customer();
    if (!customer) {
      return;
    }
    if (!customer.messagingConsent) {
      this.message.set(
        `${customer.displayName} n'a pas donné son accord pour être contacté : cochez-le dans sa fiche client`,
      );
      return;
    }
    try {
      await firstValueFrom(
        this.http.post(`${this.env.apiUrl}/v1/followup/wishes`, {
          customerId: customer.id,
          productId: product.productId,
          quantity: 1,
        }),
      );
      this.toasts.success(
        `${customer.displayName} sera prévenu(e) à l'arrivée de ${product.designation}`,
      );
      this.results.set([]);
      this.term = '';
    } catch (error) {
      this.message.set(
        (error as { error?: { detail?: string } }).error?.detail ??
          'Demande non enregistrée (serveur injoignable)',
      );
    }
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
    const reference = method === 'MOBILE_MONEY' || method === 'CARD' ? this.reference.trim() : '';
    this.cart.addPayment({
      method,
      amountCents: cents,
      thirdPartyCode: method === 'THIRD_PARTY_PAYER' ? 'AMO' : undefined,
      provider: method === 'MOBILE_MONEY' ? this.provider : undefined,
      reference: reference || undefined,
    });
    this.amount = '';
    this.reference = '';
    if (method === 'MOBILE_MONEY' && !reference) {
      const candidates = this.paymentNotifications.candidates(cents, this.provider);
      if (candidates.length === 1) {
        this.attachConfirmation(this.cart.payments().length - 1, candidates[0]);
      }
    }
  }

  /** Open confirmations that could justify this basket payment (same amount and operator). */
  protected candidatesFor(payment: CartPayment): PaymentNotification[] {
    if (payment.method !== 'MOBILE_MONEY' || payment.notificationId) {
      return [];
    }
    return this.paymentNotifications.candidates(payment.amountCents, payment.provider).slice(0, 3);
  }

  protected attachConfirmation(index: number, notification: PaymentNotification): void {
    this.cart.attachConfirmation(index, notification.reference ?? '', notification.id);
    this.paymentNotifications.consume(notification.id);
    this.toasts.success('Confirmation rattachée au règlement');
  }

  /** A confirmation just arrived: the single untyped payment of that amount and operator takes it. */
  private autoAttach(notification: PaymentNotification): void {
    const payments = this.cart.payments();
    const matches = payments
      .map((p, index) => ({ p, index }))
      .filter(
        ({ p }) =>
          p.method === 'MOBILE_MONEY' &&
          !p.notificationId &&
          !p.reference &&
          p.amountCents === notification.amountCents &&
          (!notification.provider || !p.provider || p.provider === notification.provider),
      );
    if (matches.length === 1) {
      this.attachConfirmation(matches[0].index, notification);
    }
  }

  /** Reads amount and reference out of a pasted SMS, like the server does. */
  protected readSmsText(text: string): void {
    const amount =
      /(\d{1,3}(?:[ .\u202f\u00a0]?\d{3})*)\s?(?:F\s?CFA|FCFA|XOF|CFA|F(?![A-Za-z]))/i.exec(text);
    if (amount && !this.smsForm.amount) {
      this.smsForm.amount = amount[1].replace(/[ .\u202f\u00a0]/g, '');
    }
    const reference =
      /(?:trans(?:action)?\s?id|txn\s?id|ref(?:erence)?|id)\s*[:.=]?\s*([A-Z0-9][A-Z0-9.\-_/]{4,39})/i.exec(
        text,
      );
    if (reference && !this.smsForm.reference) {
      this.smsForm.reference = reference[1].toUpperCase();
    }
    const upper = text.toUpperCase();
    for (const p of this.providers) {
      if (upper.includes(p.split('_')[0])) {
        this.smsForm.provider = p;
      }
    }
  }

  protected async recordSms(): Promise<void> {
    const amountCents = this.currency.parse(this.smsForm.amount);
    if (amountCents <= 0) {
      return;
    }
    try {
      const result = await this.paymentNotifications.record({
        provider: this.smsForm.provider,
        amountCents,
        reference: this.smsForm.reference.trim() || null,
        payerPhone: null,
        rawText: this.smsForm.rawText.trim() || null,
      });
      this.smsEntry.set(false);
      if (result.notification) {
        this.toasts.success(
          result.notification.status === 'MATCHED'
            ? 'Confirmation enregistrée et rattachée à une vente'
            : 'Confirmation enregistrée : visible sur tous les postes',
        );
        if (result.notification.status === 'UNMATCHED') {
          this.autoAttach(result.notification);
        }
      } else {
        this.toasts.info(
          'Serveur injoignable : confirmation gardée sur ce poste, envoyée dès son retour',
        );
        // the basket payment of that amount takes the typed reference right away
        const payments = this.cart.payments();
        const index = payments.findIndex(
          (p) => p.method === 'MOBILE_MONEY' && !p.reference && p.amountCents === amountCents,
        );
        if (index >= 0 && this.smsForm.reference.trim()) {
          this.cart.payments.update((list) =>
            list.map((p, i) =>
              i === index ? { ...p, reference: this.smsForm.reference.trim() } : p,
            ),
          );
        }
      }
      this.smsForm = { provider: this.smsForm.provider, amount: '', reference: '', rawText: '' };
    } catch (error) {
      this.message.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Confirmation refusée',
      );
    }
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
        if (this.overriding()) {
          this.overriding.set(null);
        } else if (this.posologyEditing()) {
          this.posologyEditing.set(null);
        } else if (this.smsEntry()) {
          this.smsEntry.set(false);
        } else if (!this.term) {
          this.cart.clear();
        }
        break;
    }
  }

  private scheduleSafetyCheck(
    customerId: string | null,
    items: { productId: string; designation: string; dci: string | null; atcCode: string | null }[],
  ): void {
    // Quantities and discounts do not change the verdict: only the products and the customer do
    const key = `${customerId ?? ''}|${items.map((i) => i.productId).join(',')}`;
    if (key === this.safetyKey) {
      return;
    }
    this.safetyKey = key;
    clearTimeout(this.safetyTimer);
    const run = ++this.safetyRun;
    if (items.length === 0) {
      this.safetyPending.set(false);
      this.cart.setSafetyAlerts([]);
      return;
    }
    this.safetyPending.set(true);
    this.safetyTimer = setTimeout(async () => {
      const verdict = await this.safety.check(customerId, items);
      if (run !== this.safetyRun) {
        return;
      }
      this.safetyPending.set(false);
      this.safetyServerChecked.set(verdict.serverChecked);
      this.safetyProfileKnown.set(verdict.profileKnown);
      const before = this.cart.blockingSafety().length;
      this.cart.setSafetyAlerts(verdict.alerts);
      if (this.cart.blockingSafety().length > before) {
        this.sound.play('refusal');
      }
    }, 250);
  }

  protected summaryOf(posology: Posology): string {
    return posologySummary(posology);
  }

  protected editPosology(line: CartLine): void {
    this.posologyDraft = { ...emptyPosology(), ...(line.posology ?? {}) };
    this.posologyEditing.set({ productId: line.productId, designation: line.designation });
  }

  protected applyPreset(preset: Partial<Posology>): void {
    this.posologyDraft = { ...emptyPosology(), ...preset, withFood: this.posologyDraft.withFood };
  }

  protected stepPosology(moment: PosologyMoment, delta: number): void {
    const next = Math.min(Math.max((Number(this.posologyDraft[moment]) || 0) + delta, 0), 20);
    this.posologyDraft = { ...this.posologyDraft, [moment]: next };
  }

  protected savePosology(productId: string): void {
    this.cart.setPosology(productId, normalizePosology(this.posologyDraft));
    this.posologyEditing.set(null);
    this.searchInput().nativeElement.focus();
  }

  protected clearPosology(productId: string): void {
    this.cart.setPosology(productId, null);
    this.posologyEditing.set(null);
  }

  protected labelCount(data: ReceiptData): number {
    return data.lines.filter((l) => !!l.posology).length;
  }

  /** The pictogram labels of the last sale, one per product with a posology, on the ticket roll. */
  protected printLabels(data: ReceiptData): void {
    const settings = this.printing.settings();
    const paper = settings.paper === 'MM80' ? 'MM80' : 'MM58';
    this.receipt.set(data);
    this.printTarget.set('labels');
    this.printFormat.set(paper);
    this.printing.printPage(paper);
  }

  protected severityLabel(alert: SafetyAlert): string {
    return SAFETY_SEVERITY_LABELS[alert.severity];
  }

  protected confirmOverride(alert: SafetyAlert): void {
    if (this.cart.overrideSafety(alert.key, this.overrideReason)) {
      this.overriding.set(null);
      this.overrideReason = '';
      this.searchInput().nativeElement.focus();
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
    this.printTarget.set('receipt');
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
          posology: l.posology ? posologySummary(l.posology) : null,
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
      const overrides = this.cart.appliedSafetyOverrides();
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
            posology: l.posology ?? undefined,
          })),
          payments: applied.map((p) => ({
            method: p.method,
            amountCents: p.amountCents,
            thirdPartyCode: p.thirdPartyCode,
            provider: p.provider,
            policyId: p.policyId,
            reference: p.reference,
          })),
          prescription: this.cart.requiresPrescription() && prescription ? prescription : undefined,
          safetyOverrides: overrides.length > 0 ? overrides : undefined,
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
      for (const payment of applied) {
        if (payment.notificationId) {
          this.paymentNotifications.consume(payment.notificationId);
        }
      }
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
