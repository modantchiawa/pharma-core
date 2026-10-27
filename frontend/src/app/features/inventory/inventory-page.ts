import { DatePipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  ExpiryWriteOff,
  StockIntegrityReport,
  StockLocation,
  StorageLocation,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { ToastService } from '../../core/ui/toast.service';
import { parseGs1 } from '../../shared/gs1';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons';
import { LocationChip } from '../../shared/ui/location-chip';
import { DailyCountCard } from '../control/daily-count-card';
import { StockExitDialog } from './stock-exit-dialog';

const DAY_MS = 86_400_000;
const EXPIRY_WINDOW_DAYS = 90;

interface StockOverview {
  productId: string;
  cip13: string;
  designation: string;
  sellingPriceCents: number | null;
  publicPriceCents: number | null;
  onHand: number;
  available: number;
  reserved: number;
  shortfall: number;
  reorderThreshold: number;
  nextExpiry: string | null;
  belowThreshold: boolean;
  location: StockLocation | null;
}

/** Location filter of the stock list: every product, those put nowhere, or one location id. */
type LocationFilter = 'ALL' | 'NONE' | string;

interface ExpiryAlert {
  lotId: string;
  designation: string;
  lotNumber: string;
  expiryDate: string;
  quantity: number;
  expired: boolean;
}

interface ReorderSuggestion {
  productId: string;
  designation: string;
  available: number;
  suggestedQuantity: number;
}

type Tab = 'stocks' | 'expiry' | 'reorder' | 'integrity' | 'writeoffs';

interface CatalogHit {
  id: string;
  cip13: string;
  data: { designation: string; ean13: string | null };
}

@Component({
  selector: 'app-inventory-page',
  imports: [
    FormsModule,
    MoneyPipe,
    DatePipe,
    HasPermissionDirective,
    Icon,
    LocationChip,
    RouterLink,
    DailyCountCard,
    StockExitDialog,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Stocks</h1>
          <p class="page-subtitle">
            Disponibilités, péremptions et réapprovisionnement de l'officine
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <a class="btn btn-secondary" routerLink="/inventory/locations">
            <app-icon name="locations" [size]="16" />
            Emplacements
          </a>
          <button type="button" class="btn btn-secondary" (click)="open(tab())">
            <app-icon name="refresh" [size]="16" />
            Actualiser
          </button>
        </div>
      </header>

      <div class="space-y-5">
        <div class="grid grid-cols-2 gap-4 xl:grid-cols-4">
          <div class="card kpi col-span-2 sm:col-span-1">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>
              Valeur du stock
            </div>
            <div class="kpi-value">{{ stockValue() | money }}</div>
            <div class="kpi-foot">Au prix de vente · {{ stocks().length }} références</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon kpi-icon-warning"
                ><app-icon name="trendDown" [size]="16"
              /></span>
              Sous le seuil
            </div>
            <div class="kpi-value" [class.text-warning]="belowThresholdCount() > 0">
              {{ belowThresholdCount() }}
            </div>
            <div class="kpi-foot">Références à réapprovisionner</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon kpi-icon-danger"><app-icon name="error" [size]="16" /></span>
              Ruptures
            </div>
            <div class="kpi-value" [class.text-danger]="outOfStockCount() > 0">
              {{ outOfStockCount() }}
            </div>
            <div class="kpi-foot">Aucune unité disponible</div>
          </div>
          <div class="card kpi col-span-2 sm:col-span-1">
            <div class="kpi-label">
              <span class="kpi-icon kpi-icon-info"><app-icon name="calendar" [size]="16" /></span>
              Péremption sous 90 j
            </div>
            <div class="kpi-value">{{ expiringCount() }}</div>
            <div class="kpi-foot">Prochain lot à écouler en priorité</div>
          </div>
        </div>

        <app-daily-count-card />

        @if (status(); as text) {
          <div class="alert alert-info" role="status">
            <app-icon name="info" [size]="16" class="mt-0.5" />
            <span>{{ text }}</span>
          </div>
        }

        <div class="-mx-1 overflow-x-auto px-1">
          <div class="segmented" role="tablist">
            @for (t of tabs; track t.id) {
              <button
                type="button"
                role="tab"
                class="inline-flex items-center gap-1.5 whitespace-nowrap"
                [attr.aria-selected]="tab() === t.id"
                (click)="open(t.id)"
              >
                <app-icon [name]="t.icon" [size]="15" />
                {{ t.label }}
              </button>
            }
          </div>
        </div>

        @switch (tab()) {
          @case ('stocks') {
            <form class="card" (ngSubmit)="receive()" *appHasPermission="'STOCK_RECEIVE'">
              <div class="card-header">
                <div class="flex items-center gap-3">
                  <span class="kpi-icon"><app-icon name="barcode" [size]="16" /></span>
                  <div>
                    <h2 class="card-title">Réception rapide</h2>
                    <p class="text-xs text-muted">
                      Scan DataMatrix GS1 (lot et péremption pré-remplis) ou EAN
                    </p>
                  </div>
                </div>
                @if (receipt.designation) {
                  <span class="badge badge-brand max-w-[50%] truncate">
                    <app-icon name="pill" [size]="13" />
                    {{ receipt.designation }}
                  </span>
                }
              </div>
              <div
                class="card-body grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)_6rem_minmax(0,1fr)_auto]"
              >
                <label class="block sm:col-span-2 lg:col-span-1">
                  <span class="label">Code de la boîte</span>
                  <span class="input-with-icon block">
                    <app-icon name="barcode" [size]="16" />
                    <input
                      class="input"
                      [(ngModel)]="receipt.scan"
                      name="scan"
                      (keydown.enter)="applyScan($event)"
                      placeholder="Scanner la boîte"
                    />
                  </span>
                </label>
                <label class="block">
                  <span class="label">Lot</span>
                  <input
                    class="input"
                    [(ngModel)]="receipt.lotNumber"
                    name="lot"
                    required
                    maxlength="40"
                  />
                </label>
                <label class="block">
                  <span class="label">Péremption</span>
                  <input
                    type="date"
                    class="input"
                    [(ngModel)]="receipt.expiryDate"
                    name="expiry"
                    required
                  />
                </label>
                <label class="block">
                  <span class="label">Qté</span>
                  <input
                    type="number"
                    min="1"
                    class="input num"
                    [(ngModel)]="receipt.quantity"
                    name="qty"
                    required
                  />
                </label>
                <label class="block">
                  <span class="label">Coût unitaire</span>
                  <input
                    inputmode="decimal"
                    class="input num"
                    [(ngModel)]="receipt.unitCost"
                    name="cost"
                    required
                  />
                </label>
                <button
                  type="submit"
                  class="btn btn-primary sm:col-span-2 lg:col-span-1"
                  [disabled]="!receipt.productId"
                >
                  <app-icon name="plus" [size]="16" />
                  Entrer en stock
                </button>
              </div>
            </form>

            <section class="card">
              <div class="card-header flex-wrap">
                <label class="input-with-icon min-w-0 flex-1 sm:max-w-md">
                  <app-icon name="search" [size]="16" />
                  <input
                    type="search"
                    class="input"
                    placeholder="Désignation ou CIP13"
                    aria-label="Rechercher un produit"
                    [(ngModel)]="query"
                    (keydown.enter)="loadStocks()"
                  />
                </label>
                <label class="btn btn-secondary btn-sm" [class.toggle-on]="belowOnly">
                  <input type="checkbox" [(ngModel)]="belowOnly" (change)="loadStocks()" />
                  Sous le seuil
                </label>
                <label class="input-with-icon w-full sm:w-64">
                  <app-icon name="mapPin" [size]="16" />
                  <select
                    class="select pl-10"
                    aria-label="Filtrer par emplacement"
                    data-testid="location-filter"
                    [(ngModel)]="locationFilter"
                    (ngModelChange)="loadStocks()"
                  >
                    <option value="ALL">Tous les emplacements</option>
                    <option value="NONE">Sans emplacement</option>
                    @for (l of locations(); track l.id) {
                      <option [value]="l.id">{{ l.code }} · {{ l.name }}</option>
                    }
                  </select>
                </label>
              </div>
              @if (selected().size > 0) {
                <div
                  class="bulk flex flex-wrap items-center gap-2 border-b border-line px-5 py-3"
                  role="region"
                  aria-label="Rangement groupé"
                >
                  <span class="text-sm font-medium">
                    {{ selected().size }} produit(s) sélectionné(s)
                  </span>
                  <select
                    class="select h-9 w-full sm:w-64"
                    aria-label="Emplacement pour la sélection"
                    [(ngModel)]="bulkLocation"
                  >
                    <option value="">Choisir un emplacement…</option>
                    <option value="NONE">Retirer l'emplacement</option>
                    @for (l of locations(); track l.id) {
                      <option [value]="l.id">{{ l.code }} · {{ l.name }}</option>
                    }
                  </select>
                  <button
                    type="button"
                    class="btn btn-primary btn-sm"
                    [disabled]="!bulkLocation || busy()"
                    (click)="assignSelection()"
                  >
                    <app-icon name="check" [size]="14" />
                    Ranger
                  </button>
                  <button type="button" class="btn btn-ghost btn-sm" (click)="clearSelection()">
                    Annuler
                  </button>
                </div>
              }
              <div class="overflow-x-auto">
                <table class="data-table">
                  <thead class="whitespace-nowrap">
                    <tr>
                      @if (canPlace()) {
                        <th class="w-10">
                          <input
                            type="checkbox"
                            aria-label="Tout sélectionner"
                            [checked]="allSelected()"
                            (change)="toggleAll()"
                          />
                        </th>
                      }
                      <th>Produit</th>
                      <th>Emplacement</th>
                      <th class="num text-right">Prix</th>
                      <th class="num text-right">Disponible</th>
                      <th class="num text-right">Réservé</th>
                      <th>Écart hors-ligne</th>
                      <th>Prochaine péremption</th>
                      <th>Statut</th>
                      @if (canAdjust()) {
                        <th><span class="sr-only">Actions</span></th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (s of stocks(); track s.productId) {
                      <tr [class.row-selected]="selected().has(s.productId)">
                        @if (canPlace()) {
                          <td>
                            <input
                              type="checkbox"
                              [attr.aria-label]="'Sélectionner ' + s.designation"
                              [checked]="selected().has(s.productId)"
                              (change)="toggle(s.productId)"
                            />
                          </td>
                        }
                        <td class="min-w-56">
                          <p class="font-medium">{{ s.designation }}</p>
                          <p class="text-xs text-subtle tabular-nums">{{ s.cip13 }}</p>
                        </td>
                        <td class="min-w-40">
                          @if (editing() === s.productId) {
                            <form
                              class="flex min-w-80 items-center gap-1.5"
                              (ngSubmit)="savePlacement(s)"
                            >
                              <select
                                class="select h-9 min-w-0 flex-1"
                                name="location"
                                [attr.aria-label]="'Emplacement de ' + s.designation"
                                [(ngModel)]="draft.locationId"
                              >
                                <option value="">Aucun</option>
                                @for (l of locations(); track l.id) {
                                  <option [value]="l.id">{{ l.code }} · {{ l.name }}</option>
                                }
                              </select>
                              <input
                                class="input h-9 w-28"
                                name="detail"
                                placeholder="case, bac…"
                                maxlength="40"
                                [attr.aria-label]="'Détail pour ' + s.designation"
                                [disabled]="!draft.locationId"
                                [(ngModel)]="draft.detail"
                              />
                              <button
                                type="submit"
                                class="btn btn-primary btn-icon btn-sm"
                                aria-label="Enregistrer l'emplacement"
                                [disabled]="busy()"
                              >
                                <app-icon name="check" [size]="14" />
                              </button>
                              <button
                                type="button"
                                class="btn btn-ghost btn-icon btn-sm"
                                aria-label="Annuler"
                                (click)="editing.set(null)"
                              >
                                <app-icon name="x" [size]="14" />
                              </button>
                            </form>
                          } @else {
                            <div class="flex items-center gap-1">
                              @if (s.location; as place) {
                                <app-location-chip [place]="place" />
                              } @else {
                                <span class="text-subtle">—</span>
                              }
                              @if (canPlace()) {
                                <button
                                  type="button"
                                  class="btn btn-ghost btn-icon btn-sm text-muted"
                                  [attr.aria-label]="'Changer l’emplacement de ' + s.designation"
                                  (click)="edit(s)"
                                >
                                  <app-icon name="pencil" [size]="14" />
                                </button>
                              }
                            </div>
                          }
                        </td>
                        <td class="num whitespace-nowrap">
                          {{ s.sellingPriceCents ?? s.publicPriceCents | money }}
                        </td>
                        <td class="num whitespace-nowrap">
                          <span class="font-semibold">{{ s.available }}</span>
                          <span class="text-subtle"> / {{ s.onHand }}</span>
                        </td>
                        <td class="num">{{ s.reserved }}</td>
                        <td class="whitespace-nowrap">
                          @if (s.shortfall > 0) {
                            <span class="badge badge-danger">{{ s.shortfall }}</span>
                            <button
                              *appHasPermission="'STOCK_ADJUST'"
                              type="button"
                              class="btn btn-ghost btn-sm ml-1"
                              (click)="resolve(s)"
                            >
                              Régulariser
                            </button>
                          } @else {
                            <span class="text-subtle">—</span>
                          }
                        </td>
                        <td class="whitespace-nowrap">
                          @if (s.nextExpiry) {
                            <span [class.text-warning]="expiresSoon(s.nextExpiry)">
                              {{ s.nextExpiry | date: 'dd/MM/yyyy' }}
                            </span>
                          } @else {
                            <span class="text-subtle">—</span>
                          }
                        </td>
                        <td>
                          @if (s.available <= 0) {
                            <span class="badge badge-danger"><span class="dot"></span>Rupture</span>
                          } @else if (s.belowThreshold) {
                            <span class="badge badge-warning"
                              ><span class="dot"></span>Stock bas</span
                            >
                          } @else if (s.nextExpiry && expiresSoon(s.nextExpiry)) {
                            <span class="badge badge-info"
                              ><span class="dot"></span>Péremption proche</span
                            >
                          } @else {
                            <span class="badge badge-success"
                              ><span class="dot"></span>En stock</span
                            >
                          }
                        </td>
                        @if (canAdjust()) {
                          <td class="text-right">
                            @if (s.onHand > 0) {
                              <button
                                type="button"
                                class="btn btn-ghost btn-sm"
                                [attr.aria-label]="'Sortie ou correction de ' + s.designation"
                                (click)="exiting.set(s)"
                              >
                                <app-icon name="minus" [size]="14" />
                                Sortie
                              </button>
                            }
                          </td>
                        }
                      </tr>
                    } @empty {
                      <tr>
                        <td [attr.colspan]="(canPlace() ? 9 : 8) + (canAdjust() ? 1 : 0)">
                          <div class="empty-state">
                            <app-icon name="stock" [size]="22" />
                            <p>Aucun produit ne correspond à cette recherche.</p>
                          </div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          }
          @case ('expiry') {
            <section class="card">
              <div class="card-header">
                <h2 class="card-title">Lots arrivant à péremption sous 90 jours</h2>
                <span class="badge">{{ expiry().length }} lot(s)</span>
              </div>
              <div class="overflow-x-auto">
                <table class="data-table">
                  <thead class="whitespace-nowrap">
                    <tr>
                      <th>Produit</th>
                      <th>Lot</th>
                      <th>Péremption</th>
                      <th class="num text-right">Qté</th>
                      <th>Statut</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (a of expiry(); track a.lotId) {
                      <tr>
                        <td class="min-w-56 font-medium">{{ a.designation }}</td>
                        <td class="font-mono text-xs">{{ a.lotNumber }}</td>
                        <td class="whitespace-nowrap">{{ a.expiryDate | date: 'dd/MM/yyyy' }}</td>
                        <td class="num">{{ a.quantity }}</td>
                        <td>
                          @if (a.expired) {
                            <span class="badge badge-danger"><span class="dot"></span>périmé</span>
                          } @else {
                            <span class="badge badge-warning">
                              <span class="dot"></span>{{ daysUntil(a.expiryDate) }} j restants
                            </span>
                          }
                        </td>
                      </tr>
                    } @empty {
                      <tr>
                        <td colspan="5">
                          <div class="empty-state">
                            <app-icon name="checkCircle" [size]="22" />
                            <p>Aucun lot ne périme dans les 90 prochains jours.</p>
                          </div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          }
          @case ('integrity') {
            <section class="card">
              <div class="card-header flex-wrap">
                <p class="max-w-2xl text-sm text-muted">
                  Chaque unité en rayon est expliquée par un mouvement ; un écart signale une
                  altération hors application, à régulariser par inventaire.
                </p>
                <button
                  *appHasPermission="'STOCK_ADJUST'"
                  type="button"
                  class="btn btn-primary"
                  (click)="runIntegrity()"
                >
                  <app-icon name="checkCircle" [size]="16" />
                  Contrôler maintenant
                </button>
              </div>
              <ul class="divide-y divide-[var(--border)]">
                @for (r of integrity(); track r.id) {
                  <li class="px-5 py-4">
                    <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                      <span class="font-medium">{{ r.runAt | date: 'dd/MM/yyyy HH:mm' }}</span>
                      <span class="text-muted">{{ r.lotsChecked }} lots contrôlés</span>
                      @if (r.mismatches > 0) {
                        <span class="badge badge-danger ml-auto">
                          <app-icon name="alert" [size]="12" />{{ r.mismatches }} écart(s)
                        </span>
                      } @else {
                        <span class="badge badge-success ml-auto">
                          <app-icon name="check" [size]="12" />{{ r.mismatches }} écart(s)
                        </span>
                      }
                    </div>
                    @if (r.details.length > 0) {
                      <div class="mt-3 overflow-x-auto rounded-[var(--radius)] border border-line">
                        <table class="data-table">
                          <thead class="whitespace-nowrap">
                            <tr>
                              <th>Type</th>
                              <th>Produit</th>
                              <th>Lot</th>
                              <th class="num text-right">Stocké</th>
                              <th class="num text-right">Calculé</th>
                            </tr>
                          </thead>
                          <tbody>
                            @for (d of r.details; track $index) {
                              <tr>
                                <td>
                                  {{
                                    d.kind === 'LOT_QUANTITY' ? 'Quantité de lot' : 'Réservations'
                                  }}
                                </td>
                                <td>{{ d.designation }}</td>
                                <td class="font-mono text-xs">{{ d.lotNumber ?? '—' }}</td>
                                <td class="num">{{ d.stored }}</td>
                                <td class="num font-semibold">{{ d.computed }}</td>
                              </tr>
                            }
                          </tbody>
                        </table>
                      </div>
                    }
                  </li>
                } @empty {
                  <li class="empty-state">
                    <app-icon name="checkCircle" [size]="22" />
                    <p>Aucun contrôle enregistré.</p>
                  </li>
                }
              </ul>
            </section>
          }
          @case ('writeoffs') {
            <section class="card">
              <div class="card-header flex-wrap">
                <p class="max-w-2xl text-sm text-muted">
                  Lots périmés sortis du stock (automatiquement chaque nuit), valorisés au coût
                  d'achat.
                </p>
                <button
                  *appHasPermission="'STOCK_ADJUST'"
                  type="button"
                  class="btn btn-secondary"
                  (click)="runWriteOff()"
                >
                  <app-icon name="trash" [size]="16" />
                  Sortir les périmés maintenant
                </button>
              </div>
              <div class="overflow-x-auto">
                <table class="data-table">
                  <thead class="whitespace-nowrap">
                    <tr>
                      <th>Date</th>
                      <th>Produit</th>
                      <th>Lot</th>
                      <th>Péremption</th>
                      <th class="num text-right">Qté</th>
                      <th class="num text-right">Valeur</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (w of writeOffs(); track w.lotId + w.writtenOffAt) {
                      <tr>
                        <td class="whitespace-nowrap">{{ w.writtenOffAt | date: 'dd/MM/yyyy' }}</td>
                        <td class="min-w-48 font-medium">{{ w.designation }}</td>
                        <td class="font-mono text-xs">{{ w.lotNumber }}</td>
                        <td class="whitespace-nowrap">{{ w.expiryDate | date: 'dd/MM/yyyy' }}</td>
                        <td class="num">{{ w.quantity }}</td>
                        <td class="num whitespace-nowrap">{{ w.valueCents | money }}</td>
                      </tr>
                    } @empty {
                      <tr>
                        <td colspan="6">
                          <div class="empty-state">
                            <app-icon name="trash" [size]="22" />
                            <p>Aucune sortie sur 90 jours</p>
                          </div>
                        </td>
                      </tr>
                    }
                    @if (writeOffs().length > 0) {
                      <tr class="bg-surface-2 font-semibold">
                        <td colspan="5">Total</td>
                        <td class="num whitespace-nowrap">{{ writeOffTotal() | money }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          }
          @case ('reorder') {
            <section class="card">
              <div class="card-header">
                <h2 class="card-title">Suggestions de réapprovisionnement</h2>
                <span class="badge">{{ reorder().length }} produit(s)</span>
              </div>
              <div class="overflow-x-auto">
                <table class="data-table">
                  <thead class="whitespace-nowrap">
                    <tr>
                      <th>Produit</th>
                      <th class="num text-right">Disponible</th>
                      <th class="num text-right">À commander</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (r of reorder(); track r.productId) {
                      <tr>
                        <td class="min-w-56 font-medium">{{ r.designation }}</td>
                        <td class="num" [class.text-danger]="r.available <= 0">
                          {{ r.available }}
                        </td>
                        <td class="num">
                          <span class="badge badge-brand">{{ r.suggestedQuantity }}</span>
                        </td>
                      </tr>
                    } @empty {
                      <tr>
                        <td colspan="3">
                          <div class="empty-state">
                            <app-icon name="checkCircle" [size]="22" />
                            <p>Aucun produit à réapprovisionner.</p>
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

    @if (exiting(); as stock) {
      <app-stock-exit-dialog
        [productId]="stock.productId"
        [designation]="stock.designation"
        (closed)="exiting.set(null)"
        (done)="exiting.set(null); loadStocks()"
      />
    }
  `,
  styles: `
    .kpi-icon-warning {
      background: var(--warning-soft);
      color: var(--warning);
    }
    .kpi-icon-danger {
      background: var(--danger-soft);
      color: var(--danger-strong);
    }
    .kpi-icon-info {
      background: var(--info-soft);
      color: var(--info);
    }
    .bulk {
      background: var(--brand-softer);
    }
    .row-selected td {
      background: var(--brand-softer);
    }
    .toggle-on {
      border-color: var(--brand-primary);
      background: var(--brand-soft);
      color: var(--brand-text);
    }
  `,
})
export class InventoryPage {
  private readonly http = inject(HttpClient);
  private readonly currency = inject(CurrencyService);
  private readonly permissions = inject(PermissionService);
  private readonly toasts = inject(ToastService);
  private readonly apiBase = inject(APP_ENVIRONMENT).apiUrl;
  private readonly api = `${this.apiBase}/v1/inventory`;

  protected readonly tabs: { id: Tab; label: string; icon: IconName }[] = [
    { id: 'stocks', label: 'Stocks', icon: 'stock' },
    { id: 'expiry', label: 'Péremptions (90 j)', icon: 'calendar' },
    { id: 'writeoffs', label: 'Sorties périmés', icon: 'trash' },
    { id: 'reorder', label: 'Réapprovisionnement', icon: 'procurement' },
    { id: 'integrity', label: 'Intégrité', icon: 'checkCircle' },
  ];
  protected readonly tab = signal<Tab>('stocks');
  protected readonly stocks = signal<StockOverview[]>([]);
  protected readonly stockValue = computed(() =>
    this.stocks().reduce(
      (sum, s) => sum + s.onHand * (s.sellingPriceCents ?? s.publicPriceCents ?? 0),
      0,
    ),
  );
  protected readonly belowThresholdCount = computed(
    () => this.stocks().filter((s) => s.belowThreshold).length,
  );
  protected readonly outOfStockCount = computed(
    () => this.stocks().filter((s) => s.available <= 0).length,
  );
  protected readonly expiringCount = computed(
    () => this.stocks().filter((s) => s.nextExpiry && this.expiresSoon(s.nextExpiry)).length,
  );
  protected readonly expiry = signal<ExpiryAlert[]>([]);
  protected readonly reorder = signal<ReorderSuggestion[]>([]);
  protected readonly integrity = signal<StockIntegrityReport[]>([]);
  protected readonly writeOffs = signal<ExpiryWriteOff[]>([]);
  protected readonly status = signal<string | null>(null);
  protected readonly locations = signal<StorageLocation[]>([]);
  protected readonly canPlace = computed(() => this.permissions.has('STOCK_LOCATION_MANAGE'));
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly allSelected = computed(
    () => this.stocks().length > 0 && this.stocks().every((s) => this.selected().has(s.productId)),
  );
  protected readonly editing = signal<string | null>(null);
  /** Product whose stock exit (or correction) dialog is open. */
  protected readonly exiting = signal<StockOverview | null>(null);
  protected readonly canAdjust = computed(() => this.permissions.has('STOCK_ADJUST'));
  protected readonly busy = signal(false);
  protected draft = { locationId: '', detail: '' };
  protected bulkLocation = '';
  protected locationFilter: LocationFilter =
    inject(ActivatedRoute).snapshot.queryParamMap.get('location') ?? 'ALL';
  protected query = '';
  protected belowOnly = false;
  protected receipt = {
    scan: '',
    productId: '',
    designation: '',
    lotNumber: '',
    expiryDate: '',
    quantity: 1,
    unitCost: '',
  };

  constructor() {
    void this.loadStocks();
    void this.loadLocations();
  }

  protected async open(tab: Tab): Promise<void> {
    this.tab.set(tab);
    if (tab === 'expiry') {
      this.expiry.set(
        await firstValueFrom(
          this.http.get<ExpiryAlert[]>(`${this.api}/expiry-alerts`, { params: { withinDays: 90 } }),
        ),
      );
    } else if (tab === 'reorder') {
      this.reorder.set(
        await firstValueFrom(this.http.get<ReorderSuggestion[]>(`${this.api}/reorder-suggestions`)),
      );
    } else if (tab === 'integrity') {
      await this.loadIntegrity();
    } else if (tab === 'writeoffs') {
      await this.loadWriteOffs();
    } else {
      await this.loadStocks();
    }
  }

  protected async loadStocks(): Promise<void> {
    let params = new HttpParams().set('q', this.query).set('belowThreshold', this.belowOnly);
    if (this.locationFilter === 'NONE') {
      params = params.set('unassigned', true);
    } else if (this.locationFilter !== 'ALL') {
      params = params.set('locationId', this.locationFilter);
    }
    this.stocks.set(
      await firstValueFrom(this.http.get<StockOverview[]>(`${this.api}/stocks`, { params })),
    );
    const visible = new Set(this.stocks().map((s) => s.productId));
    this.selected.update((current) => new Set([...current].filter((id) => visible.has(id))));
  }

  private async loadLocations(): Promise<void> {
    this.locations.set(
      await firstValueFrom(this.http.get<StorageLocation[]>(`${this.api}/locations`)),
    );
  }

  protected toggle(productId: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (!next.delete(productId)) {
        next.add(productId);
      }
      return next;
    });
  }

  protected toggleAll(): void {
    this.selected.set(
      this.allSelected() ? new Set() : new Set(this.stocks().map((s) => s.productId)),
    );
  }

  protected clearSelection(): void {
    this.selected.set(new Set());
    this.bulkLocation = '';
  }

  protected edit(stock: StockOverview): void {
    this.draft = { locationId: stock.location?.id ?? '', detail: stock.location?.detail ?? '' };
    this.editing.set(stock.productId);
  }

  protected async savePlacement(stock: StockOverview): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(
        this.http.put(`${this.api}/stocks/${stock.productId}/location`, {
          locationId: this.draft.locationId || null,
          detail: this.draft.locationId ? this.draft.detail.trim() || null : null,
        }),
      );
      this.editing.set(null);
      this.toasts.success(`Emplacement de ${stock.designation} enregistré`);
      await Promise.all([this.loadStocks(), this.loadLocations()]);
    } catch (error) {
      this.toasts.error(problemDetail(error, 'Emplacement refusé'));
    } finally {
      this.busy.set(false);
    }
  }

  protected async assignSelection(): Promise<void> {
    const productIds = [...this.selected()];
    const target = this.bulkLocation === 'NONE' ? null : this.bulkLocation;
    this.busy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<{ changed: number }>(`${this.api}/stocks/location-assignments`, {
          productIds,
          locationId: target,
        }),
      );
      const where = this.locations().find((l) => l.id === target);
      this.toasts.success(
        where
          ? `${result.changed} produit(s) rangé(s) en ${where.code}`
          : `Emplacement retiré pour ${result.changed} produit(s)`,
      );
      this.clearSelection();
      await Promise.all([this.loadStocks(), this.loadLocations()]);
    } catch (error) {
      this.toasts.error(problemDetail(error, 'Rangement refusé'));
    } finally {
      this.busy.set(false);
    }
  }

  protected daysUntil(date: string): number {
    return Math.ceil((new Date(date).getTime() - Date.now()) / DAY_MS);
  }

  protected expiresSoon(date: string): boolean {
    return this.daysUntil(date) <= EXPIRY_WINDOW_DAYS;
  }

  protected writeOffTotal(): number {
    return this.writeOffs().reduce((sum, w) => sum + w.valueCents, 0);
  }

  protected async runIntegrity(): Promise<void> {
    this.status.set(null);
    try {
      const report = await firstValueFrom(
        this.http.post<StockIntegrityReport>(`${this.api}/integrity-checks`, {}),
      );
      this.status.set(
        report.mismatches === 0
          ? `Contrôle terminé : ${report.lotsChecked} lots cohérents`
          : `${report.mismatches} écart(s) détecté(s) sur ${report.lotsChecked} lots`,
      );
      await this.loadIntegrity();
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Contrôle refusé',
      );
    }
  }

  protected async runWriteOff(): Promise<void> {
    this.status.set(null);
    try {
      const removed = await firstValueFrom(
        this.http.post<ExpiryWriteOff[]>(`${this.api}/write-offs/run`, {}),
      );
      this.status.set(`${removed.length} lot(s) périmé(s) sorti(s) du stock`);
      await this.loadWriteOffs();
    } catch (error) {
      this.status.set((error as { error?: { detail?: string } }).error?.detail ?? 'Sortie refusée');
    }
  }

  /** A GS1 code gives product, lot and expiry at once; a plain EAN/CIP only the product. */
  protected async applyScan(event: Event): Promise<void> {
    event.preventDefault();
    const raw = this.receipt.scan.trim();
    if (!raw) {
      return;
    }
    const gs1 = parseGs1(raw);
    const code = gs1?.ean13 ?? raw;
    if (gs1?.lot) {
      this.receipt.lotNumber = gs1.lot;
    }
    if (gs1?.expiryDate) {
      this.receipt.expiryDate = gs1.expiryDate;
    }
    try {
      const hits = await firstValueFrom(
        this.http.get<CatalogHit[]>(`${this.apiBase}/v1/catalog/products`, {
          params: { q: code, limit: 5 },
        }),
      );
      const hit = hits.find((h) => h.cip13 === code || h.data.ean13 === code) ?? hits[0];
      if (!hit) {
        this.status.set(`Aucun produit du catalogue pour « ${code} »`);
        this.receipt.productId = '';
        this.receipt.designation = '';
        return;
      }
      this.receipt.productId = hit.id;
      this.receipt.designation = hit.data.designation;
      this.status.set(null);
    } catch {
      this.status.set('Catalogue injoignable');
    }
  }

  protected async receive(): Promise<void> {
    if (!this.receipt.productId) {
      return;
    }
    this.status.set(null);
    try {
      await firstValueFrom(
        this.http.post(`${this.api}/lots`, {
          productId: this.receipt.productId,
          lotNumber: this.receipt.lotNumber,
          expiryDate: this.receipt.expiryDate,
          quantity: Number(this.receipt.quantity),
          unitCostCents: this.currency.parse(this.receipt.unitCost),
        }),
      );
      this.status.set(
        `${this.receipt.quantity} × ${this.receipt.designation} (lot ${this.receipt.lotNumber}) en stock`,
      );
      this.receipt = {
        scan: '',
        productId: '',
        designation: '',
        lotNumber: '',
        expiryDate: '',
        quantity: 1,
        unitCost: '',
      };
      await this.loadStocks();
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Réception refusée',
      );
    }
  }

  private async loadIntegrity(): Promise<void> {
    this.integrity.set(
      await firstValueFrom(
        this.http.get<StockIntegrityReport[]>(`${this.api}/integrity-checks`, {
          params: { limit: 10 },
        }),
      ),
    );
  }

  private async loadWriteOffs(): Promise<void> {
    this.writeOffs.set(
      await firstValueFrom(this.http.get<ExpiryWriteOff[]>(`${this.api}/write-offs`)),
    );
  }

  protected async resolve(stock: StockOverview): Promise<void> {
    const reason = prompt(`Motif de régularisation pour ${stock.designation}`);
    if (reason) {
      await firstValueFrom(
        this.http.post(`${this.api}/stocks/${stock.productId}/shortfall-resolution`, { reason }),
      );
      await this.loadStocks();
    }
  }
}

function problemDetail(error: unknown, fallback: string): string {
  return (error as { error?: { detail?: string } }).error?.detail ?? fallback;
}
