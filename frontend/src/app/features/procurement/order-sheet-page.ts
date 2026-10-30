import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  firstValueFrom,
  of,
  Subject,
  switchMap,
} from 'rxjs';
import {
  ProposalLine,
  ProposalSheet,
  PurchaseOrderGeneration,
  PurchaseOrderScope,
  PurchaseOrderSummary,
  Supplier,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { OrderDetail } from './order-detail';
import { OrderTracking } from './order-tracking';
import {
  errorDetail,
  generationPreview,
  isoToday,
  ManualAddition,
  matchesProduct,
  SCOPES,
  withAdditions,
} from './purchase-orders';

const REASON_LABELS: Record<string, string> = {
  BELOW_TARGET: 'sous la couverture',
  BELOW_THRESHOLD: 'sous le seuil',
  EXPIRING_REPLACEMENT: 'péremption proche',
  LOST_SALES: 'ventes perdues',
  SEASONAL_UPTURN: 'saison haute',
  MANUAL: 'ajout manuel',
};

const REASON_BADGE: Record<string, string> = {
  BELOW_THRESHOLD: 'badge-warning',
  EXPIRING_REPLACEMENT: 'badge-info',
  LOST_SALES: 'badge-danger',
  SEASONAL_UPTURN: 'badge-warning',
  MANUAL: 'badge-brand',
};

type Tab = 'proposal' | 'orders';

/**
 * Tomorrow's order sheet and the follow-up of purchase orders: the system proposes, the
 * pharmacist adjusts quantities and generates one order per supplier, sends them (WhatsApp,
 * e-mail, print), then tracks deliveries and receives them lot by lot. Products the proposal
 * does not suggest (exceptional need, bulk purchase) are searched in the catalogue and added by hand.
 * Query parameters: {@code tab=orders}, {@code scope=OVERDUE}, {@code order=<id>}.
 */
@Component({
  selector: 'app-order-sheet-page',
  imports: [
    FormsModule,
    MoneyPipe,
    DatePipe,
    DecimalPipe,
    HasPermissionDirective,
    Icon,
    OrderTracking,
    OrderDetail,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in print:hidden">
      <header class="page-header">
        <div>
          <h1 class="page-title">Commandes fournisseurs</h1>
          <p class="page-subtitle">
            Le système propose, vous ajustez, les commandes partent au fournisseur et se suivent
            jusqu'à la livraison
          </p>
        </div>
        @if (activeTab() === 'proposal') {
          <button
            type="button"
            class="btn btn-primary"
            *appHasPermission="'PROCUREMENT_MANAGE'"
            [disabled]="generating() || preview().suppliers.length === 0"
            (click)="generate()"
            data-testid="generate-orders"
          >
            <app-icon name="sparkles" [size]="16" />
            Générer les commandes
            @if (preview().suppliers.length > 0) {
              ({{ preview().suppliers.length }})
            }
          </button>
        }
      </header>

      <div class="space-y-5">
        <div class="segmented max-w-full overflow-x-auto" role="tablist">
          <button
            type="button"
            role="tab"
            class="inline-flex items-center gap-1.5 whitespace-nowrap"
            [attr.aria-selected]="activeTab() === 'proposal'"
            (click)="activeTab.set('proposal')"
          >
            <app-icon name="spreadsheet" [size]="15" />
            Fiche<span class="hidden sm:inline">de commande</span>
          </button>
          <button
            type="button"
            role="tab"
            class="inline-flex items-center gap-1.5 whitespace-nowrap"
            [attr.aria-selected]="activeTab() === 'orders'"
            (click)="activeTab.set('orders')"
          >
            <app-icon name="procurement" [size]="15" />
            Suivi<span class="hidden sm:inline">des commandes</span>
            @if ((summary()?.overdue ?? 0) > 0) {
              <span class="badge badge-danger">{{ summary()?.overdue }}</span>
            }
          </button>
        </div>

        @if (activeTab() === 'proposal') {
          @if (sheet(); as sheet) {
            <div class="grid grid-cols-2 gap-4 xl:grid-cols-4">
              <div class="card kpi col-span-2 sm:col-span-1">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>
                  Estimation
                </div>
                <div class="kpi-value">{{ preview().totalCents | money }}</div>
                <div class="kpi-foot">Au prix d'achat des quantités retenues</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="spreadsheet" [size]="16" /></span>
                  Lignes proposées
                </div>
                <div class="kpi-value">{{ sheet.lines.length }}</div>
                <div class="kpi-foot">
                  {{ selectedCount() }} retenue(s)
                  @if (additions().length > 0) {
                    · {{ additions().length }} ajoutée(s)
                  }
                </div>
              </div>
              <div class="card kpi col-span-2 max-sm:order-last sm:col-span-1">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
                  Livraison visée
                </div>
                <div class="kpi-value">{{ sheet.date | date: 'dd/MM/yyyy' }}</div>
                <div class="kpi-foot">Fiche du jour pour demain</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="activity" [size]="16" /></span>
                  Vitesse de vente
                </div>
                <div class="kpi-value">{{ sheet.periodDays }} jours</div>
                <div class="kpi-foot">Période d'observation des ventes</div>
              </div>
            </div>
          }

          @if (preview().suppliers.length > 0) {
            <section class="card" aria-label="Commandes qui seront générées">
              <div class="card-header">
                <h2 class="card-title">Commandes qui seront générées</h2>
                <span class="text-sm text-muted"
                  >Un brouillon par fournisseur, à vérifier avant l'envoi</span
                >
              </div>
              <ul class="grid gap-3 p-4 sm:grid-cols-2 xl:grid-cols-3">
                @for (p of preview().suppliers; track p.supplierId) {
                  <li
                    class="rounded-[var(--radius)] border p-3"
                    [class.border-line]="!p.belowMinimum"
                    [class.border-[var(--warning)]]="p.belowMinimum"
                    [attr.data-testid]="'preview-' + p.supplierId"
                  >
                    <div class="flex items-start justify-between gap-2">
                      <p class="font-medium">{{ p.supplierName }}</p>
                      <p class="num font-semibold">{{ p.estimatedCents | money }}</p>
                    </div>
                    <p class="text-sm text-muted">
                      {{ p.lineCount }} ligne(s) · {{ p.units }} unité(s)
                    </p>
                    @if (p.belowMinimum) {
                      <p class="mt-1.5 flex items-start gap-1.5 text-xs text-warning">
                        <app-icon name="alert" [size]="13" class="mt-0.5" />
                        Sous le minimum de {{ p.minOrderCents | money }} : l'envoi sera refusé,
                        complétez la commande
                      </p>
                    }
                  </li>
                }
              </ul>
            </section>
          }

          @if (preview().unassigned.length > 0) {
            <section class="card" aria-label="Produits sans fournisseur">
              <div class="p-4 pb-0">
                <div class="alert alert-warning">
                  <app-icon name="alert" [size]="16" class="mt-0.5" />
                  <span>
                    <strong>{{ preview().unassigned.length }} produit(s) sans fournisseur</strong> :
                    associez-les pour qu'ils entrent dans une commande.
                  </span>
                </div>
              </div>
              <ul class="divide-y divide-line">
                @for (line of preview().unassigned; track line.productId) {
                  <li class="flex flex-wrap items-center gap-2 px-4 py-2.5">
                    <div class="min-w-0 flex-1 basis-48">
                      <p class="truncate text-sm font-medium">{{ line.designation }}</p>
                      <p class="text-xs text-subtle">
                        {{ merged().quantities[line.productId] }} à commander
                      </p>
                    </div>
                    <ng-container *appHasPermission="'PROCUREMENT_MANAGE'">
                      <select
                        class="select min-h-8 w-full sm:w-52"
                        [attr.aria-label]="'Fournisseur pour ' + line.designation"
                        [(ngModel)]="links[line.productId]"
                      >
                        <option [ngValue]="undefined">Choisir un fournisseur</option>
                        @for (s of suppliers(); track s.id) {
                          <option [ngValue]="s.id">{{ s.details.name }}</option>
                        }
                      </select>
                      <button
                        type="button"
                        class="btn btn-secondary btn-sm"
                        [disabled]="!links[line.productId]"
                        (click)="associate(line)"
                      >
                        Associer
                      </button>
                    </ng-container>
                  </li>
                }
              </ul>
            </section>
          }

          <section
            class="card"
            aria-label="Ajouter des produits"
            *appHasPermission="'PROCUREMENT_MANAGE'"
            data-testid="manual-additions"
          >
            <div class="card-header flex-wrap">
              <div>
                <h2 class="card-title">Ajouter des produits</h2>
                <p class="text-sm text-muted">
                  Produit exceptionnel, commande spéciale ou achat en grande quantité : cherchez-le
                  dans tout le catalogue
                </p>
              </div>
            </div>
            <div class="space-y-3 p-4">
              <label class="relative block">
                <span class="sr-only">Rechercher un produit à ajouter</span>
                <app-icon
                  name="search"
                  [size]="16"
                  class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-subtle"
                />
                <input
                  type="search"
                  class="input w-full pl-9"
                  placeholder="Nom, DCI ou code CIP / EAN (2 caractères minimum)"
                  autocomplete="off"
                  [ngModel]="addTerm()"
                  (ngModelChange)="searchToAdd($event)"
                  (keydown.escape)="searchToAdd('')"
                  data-testid="add-product-search"
                />
              </label>
              @if (addTerm().trim().length >= 2) {
                <ul
                  class="max-h-80 divide-y divide-line overflow-y-auto rounded-[var(--radius)] border"
                  aria-label="Produits trouvés"
                >
                  @for (found of addResults(); track found.productId) {
                    <li class="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2">
                      <div class="min-w-0 flex-1 basis-56">
                        <p class="truncate text-sm font-medium">{{ found.designation }}</p>
                        <p class="text-xs text-subtle tabular-nums">
                          {{ found.cip13 }} · {{ found.supplierName ?? 'sans fournisseur' }}
                          @if (found.purchasePriceCents !== null) {
                            · {{ found.purchasePriceCents | money }}
                          }
                        </p>
                      </div>
                      <p class="text-xs text-muted tabular-nums">
                        Dispo {{ found.available }} · En cmd {{ found.onOrder }} ·
                        {{ found.dailyDemand | number: '1.1-1' }}/j
                      </p>
                      @if (isOnSheet(found.productId)) {
                        <span class="badge">Déjà sur la fiche</span>
                      } @else {
                        <button
                          type="button"
                          class="btn btn-secondary btn-sm"
                          (click)="addProduct(found)"
                          [attr.data-testid]="'add-' + found.productId"
                        >
                          <app-icon name="plus" [size]="14" />
                          Ajouter
                        </button>
                      }
                    </li>
                  } @empty {
                    <li class="px-3 py-3 text-sm text-muted">
                      {{ searching() ? 'Recherche…' : 'Aucun produit trouvé' }}
                    </li>
                  }
                </ul>
              }
            </div>
            @if (additions().length > 0) {
              <div class="overflow-x-auto border-t">
                <table class="data-table">
                  <thead class="whitespace-nowrap">
                    <tr>
                      <th>Produit ajouté</th>
                      <th class="num text-right">Dispo</th>
                      <th class="num text-right">En cmd</th>
                      <th class="num text-right">Vente/j</th>
                      <th>Fournisseur</th>
                      <th class="num text-right">Quantité</th>
                      <th class="num text-right">Estimation</th>
                      <th class="w-10"><span class="sr-only">Retirer</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (a of additions(); track a.line.productId) {
                      <tr [attr.data-testid]="'addition-' + a.line.productId">
                        <td class="min-w-56">
                          <p class="font-medium">{{ a.line.designation }}</p>
                          <p class="text-xs text-subtle tabular-nums">{{ a.line.cip13 }}</p>
                        </td>
                        <td class="num">{{ a.line.available }}</td>
                        <td class="num">{{ a.line.onOrder }}</td>
                        <td class="num">{{ a.line.dailyDemand | number: '1.1-1' }}</td>
                        <td>
                          <select
                            class="select min-h-8 w-full sm:w-52"
                            [attr.aria-label]="'Fournisseur pour ' + a.line.designation"
                            [ngModel]="a.supplierId ?? a.line.supplierId"
                            (ngModelChange)="updateAddition(a, { supplierId: $event })"
                          >
                            <option [ngValue]="null">Choisir un fournisseur</option>
                            @for (s of suppliers(); track s.id) {
                              <option [ngValue]="s.id">
                                {{ s.details.name
                                }}{{ s.id === a.line.supplierId ? ' (habituel)' : '' }}
                              </option>
                            }
                          </select>
                        </td>
                        <td class="num">
                          <input
                            type="number"
                            min="1"
                            [step]="a.line.packSize"
                            class="input num ml-auto min-h-8 w-24"
                            [attr.aria-label]="'Quantité à commander, ' + a.line.designation"
                            [ngModel]="a.quantity"
                            (ngModelChange)="updateAddition(a, { quantity: $event })"
                          />
                        </td>
                        <td class="num whitespace-nowrap">
                          @if (a.line.purchasePriceCents !== null) {
                            {{ a.line.purchasePriceCents * a.quantity | money }}
                          } @else {
                            <span class="text-subtle">—</span>
                          }
                        </td>
                        <td>
                          <button
                            type="button"
                            class="btn btn-ghost btn-icon btn-sm"
                            [attr.aria-label]="'Retirer ' + a.line.designation"
                            (click)="removeAddition(a)"
                          >
                            <app-icon name="x" [size]="14" />
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>

          <section class="card">
            <div class="card-header flex-wrap">
              <div class="flex min-w-0 flex-1 flex-wrap items-center gap-2">
                <label class="relative w-full sm:w-72">
                  <span class="sr-only">Rechercher dans la fiche</span>
                  <app-icon
                    name="search"
                    [size]="16"
                    class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-subtle"
                  />
                  <input
                    type="search"
                    class="input w-full pl-9"
                    placeholder="Rechercher dans la fiche"
                    [ngModel]="filter()"
                    (ngModelChange)="filter.set($event)"
                    data-testid="sheet-filter"
                  />
                </label>
                <select
                  class="select w-full sm:w-64"
                  aria-label="Fournisseur"
                  [(ngModel)]="supplierId"
                  (ngModelChange)="loadProposal()"
                >
                  <option [ngValue]="null">Tous les fournisseurs</option>
                  @for (s of suppliers(); track s.id) {
                    <option [ngValue]="s.id">{{ s.details.name }}</option>
                  }
                </select>
                <button type="button" class="btn btn-secondary" (click)="loadProposal()">
                  <app-icon name="refresh" [size]="16" />
                  Recalculer
                </button>
              </div>
            </div>
            <div class="overflow-x-auto">
              <table class="data-table">
                <thead class="whitespace-nowrap">
                  <tr>
                    <th class="w-10"><span class="sr-only">Sélection</span></th>
                    <th>Produit</th>
                    <th>Fournisseur</th>
                    <th class="num text-right">Dispo</th>
                    <th class="num text-right">En cmd</th>
                    <th class="num text-right">Vente/j</th>
                    <th class="num text-right">Cible</th>
                    <th class="num text-right">Proposé</th>
                    <th class="num text-right">À commander</th>
                    <th>Motif</th>
                  </tr>
                </thead>
                <tbody>
                  @for (line of visibleLines(); track line.productId) {
                    <tr>
                      <td>
                        <input
                          type="checkbox"
                          [attr.aria-label]="'Commander ' + line.designation"
                          [checked]="quantities()[line.productId] > 0"
                          (change)="toggle(line, $event)"
                        />
                      </td>
                      <td class="min-w-56">
                        <p class="font-medium">{{ line.designation }}</p>
                        <p class="text-xs text-subtle tabular-nums">{{ line.cip13 }}</p>
                      </td>
                      <td class="whitespace-nowrap">
                        @if (line.supplierName) {
                          <span class="text-muted">{{ line.supplierName }}</span>
                        } @else {
                          <span class="badge badge-warning">Sans fournisseur</span>
                        }
                      </td>
                      <td class="num whitespace-nowrap">
                        {{ line.available }}
                        @if (line.expiringSoon > 0) {
                          <span class="badge badge-warning ml-1"
                            >-{{ line.expiringSoon }} périm.</span
                          >
                        }
                      </td>
                      <td class="num">{{ line.onOrder }}</td>
                      <td class="num">
                        {{ line.dailyDemand | number: '1.1-1' }}
                        @if (line.seasonalFactor !== 1) {
                          <span
                            class="ml-1 text-xs text-muted"
                            title="Ventes des prochaines semaines l'an dernier, rapportées à la moyenne de l'année"
                            >×{{ line.seasonalFactor | number: '1.1-1' }}</span
                          >
                        }
                      </td>
                      <td class="num">{{ line.targetStock }}</td>
                      <td class="num whitespace-nowrap">
                        {{ line.suggestedQuantity }}
                        <span class="text-xs text-subtle">×{{ line.packSize }}</span>
                      </td>
                      <td class="num">
                        <input
                          type="number"
                          min="0"
                          [step]="line.packSize"
                          class="input num ml-auto min-h-8 w-20"
                          [attr.aria-label]="'Quantité à commander, ' + line.designation"
                          [ngModel]="quantities()[line.productId]"
                          (ngModelChange)="setQuantity(line, $event)"
                        />
                      </td>
                      <td>
                        <div class="flex flex-wrap gap-1">
                          @for (r of line.reasons; track r) {
                            <span class="badge" [class]="reasonBadge(r)">{{ reasonLabel(r) }}</span>
                          }
                        </div>
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="10">
                        <div class="empty-state">
                          <app-icon name="checkCircle" [size]="22" />
                          @if (filter().trim() && (sheet()?.lines?.length ?? 0) > 0) {
                            <p>Aucune ligne de la fiche ne correspond à « {{ filter() }} ».</p>
                          } @else {
                            <p>Rien à commander : le stock couvre la demande prévue.</p>
                          }
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        } @else {
          <app-order-tracking
            [suppliers]="suppliers()"
            [refresh]="refresh()"
            [(scope)]="trackingScope"
            (selected)="selectedOrder.set($event)"
            (summaryLoaded)="summary.set($event)"
          />
        }
      </div>
    </div>

    @if (selectedOrder(); as id) {
      <app-order-detail
        [orderId]="id"
        [today]="summary()?.today ?? today"
        (closed)="selectedOrder.set(null)"
        (changed)="refresh.update((n) => n + 1)"
      />
    }
  `,
})
export class OrderSheetPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/procurement`;
  private readonly toast = inject(ToastService);

  /** Query parameters (router input binding). */
  readonly tab = input<string>();
  readonly scope = input<string>();
  readonly order = input<string>();

  protected readonly activeTab = linkedSignal<Tab>(() =>
    this.tab() === 'orders' ? 'orders' : 'proposal',
  );
  protected readonly trackingScope = linkedSignal<PurchaseOrderScope>(() => {
    const requested = this.scope();
    return SCOPES.find((s) => s.value === requested)?.value ?? 'OPEN';
  });
  protected readonly selectedOrder = linkedSignal<string | null>(() => this.order() ?? null);
  protected readonly suppliers = signal<Supplier[]>([]);
  protected readonly sheet = signal<ProposalSheet | null>(null);
  protected readonly quantities = signal<Record<string, number>>({});
  protected readonly summary = signal<PurchaseOrderSummary | null>(null);
  protected readonly refresh = signal(0);
  protected readonly generating = signal(false);
  protected readonly today = isoToday();
  protected readonly selectedCount = computed(
    () => Object.values(this.quantities()).filter((q) => q > 0).length,
  );
  /** Products added by hand, kept across recalculations until the orders are generated. */
  protected readonly additions = signal<ManualAddition[]>([]);
  protected readonly filter = signal('');
  protected readonly visibleLines = computed(() =>
    (this.sheet()?.lines ?? []).filter((l) => matchesProduct(l, this.filter())),
  );
  protected readonly addTerm = signal('');
  protected readonly addResults = signal<ProposalLine[]>([]);
  protected readonly searching = signal(false);
  private readonly addSearch = new Subject<string>();
  /** The sheet with the products added by hand, as the server will group it. */
  protected readonly merged = computed(() =>
    withAdditions(this.sheet()?.lines ?? [], this.quantities(), this.additions(), this.suppliers()),
  );
  protected readonly preview = computed(() =>
    generationPreview(this.merged().lines, this.merged().quantities, this.suppliers()),
  );
  protected supplierId: string | null = null;
  /** Supplier chosen for each product that has none yet. */
  protected links: Record<string, string | undefined> = {};

  constructor() {
    void this.init();
    this.addSearch
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((term) => {
          if (term.length < 2) {
            return of([]);
          }
          this.searching.set(true);
          return this.http
            .get<ProposalLine[]>(`${this.api}/proposal/products`, { params: { q: term } })
            .pipe(catchError(() => of([])));
        }),
        takeUntilDestroyed(),
      )
      .subscribe((found) => {
        this.searching.set(false);
        this.addResults.set(found);
      });
  }

  protected searchToAdd(term: string): void {
    this.addTerm.set(term ?? '');
    if ((term ?? '').trim().length < 2) {
      this.addResults.set([]);
    }
    this.addSearch.next((term ?? '').trim());
  }

  protected isOnSheet(productId: string): boolean {
    return (
      this.additions().some((a) => a.line.productId === productId) ||
      (this.sheet()?.lines ?? []).some((l) => l.productId === productId)
    );
  }

  /** Adds a product found in the catalogue, one pack by default, to its usual supplier. */
  protected addProduct(line: ProposalLine): void {
    if (this.isOnSheet(line.productId)) {
      return;
    }
    this.additions.update((list) => [
      ...list,
      { line, quantity: Math.max(line.packSize, 1), supplierId: line.supplierId },
    ]);
    this.toast.success(`${line.designation} ajouté à la fiche`);
    this.searchToAdd('');
  }

  protected updateAddition(
    addition: ManualAddition,
    change: { quantity?: number; supplierId?: string | null },
  ): void {
    this.additions.update((list) =>
      list.map((a) =>
        a.line.productId !== addition.line.productId
          ? a
          : {
              ...a,
              quantity:
                change.quantity === undefined
                  ? a.quantity
                  : Math.max(Number(change.quantity) || 0, 0),
              supplierId: change.supplierId === undefined ? a.supplierId : change.supplierId,
            },
      ),
    );
  }

  protected removeAddition(addition: ManualAddition): void {
    this.additions.update((list) =>
      list.filter((a) => a.line.productId !== addition.line.productId),
    );
  }

  protected reasonLabel(reason: string): string {
    return REASON_LABELS[reason] ?? reason;
  }

  protected reasonBadge(reason: string): string {
    return REASON_BADGE[reason] ?? '';
  }

  protected toggle(line: ProposalLine, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.setQuantity(line, checked ? line.suggestedQuantity : 0);
  }

  protected setQuantity(line: ProposalLine, value: number): void {
    this.quantities.update((q) => ({ ...q, [line.productId]: Math.max(Number(value) || 0, 0) }));
  }

  /** Recomputes the sheet; quantities the pharmacist already changed are kept. */
  protected async loadProposal(keepEdits = false): Promise<void> {
    const params: Record<string, string> = this.supplierId ? { supplierId: this.supplierId } : {};
    try {
      const sheet = await firstValueFrom(
        this.http.get<ProposalSheet>(`${this.api}/proposal`, { params }),
      );
      const previous = keepEdits ? this.quantities() : {};
      this.sheet.set(sheet);
      this.quantities.set(
        Object.fromEntries(
          sheet.lines.map((l) => [l.productId, previous[l.productId] ?? l.suggestedQuantity]),
        ),
      );
    } catch (error) {
      this.toast.error(errorDetail(error, 'Fiche de commande indisponible'));
    }
  }

  protected async generate(): Promise<void> {
    const sheet = this.sheet();
    if (!sheet || this.generating()) {
      return;
    }
    const additions = this.additions()
      .filter((a) => a.quantity > 0)
      .map((a) => ({
        productId: a.line.productId,
        quantity: a.quantity,
        supplierId: a.supplierId ?? a.line.supplierId,
      }));
    const overrides = sheet.lines
      .filter(
        (l) => (this.quantities()[l.productId] ?? l.suggestedQuantity) !== l.suggestedQuantity,
      )
      .map((l) => ({ productId: l.productId, quantity: this.quantities()[l.productId] }));
    this.generating.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<PurchaseOrderGeneration>(`${this.api}/orders/generate`, {
          supplierId: this.supplierId,
          overrides,
          additions,
        }),
      );
      // a product added without any supplier stays on the sheet until it is linked to one
      const unlinked = new Set(result.unassigned.map((l) => l.productId));
      this.additions.update((list) => list.filter((a) => unlinked.has(a.line.productId)));
      const parts = [];
      if (result.created > 0) {
        parts.push(`${result.created} créée(s)`);
      }
      if (result.updated > 0) {
        parts.push(`${result.updated} brouillon(s) mis à jour`);
      }
      const orphans =
        result.unassigned.length > 0
          ? ` · ${result.unassigned.length} produit(s) sans fournisseur`
          : '';
      this.toast.success(`Commandes générées : ${parts.join(', ')}${orphans}`);
      this.trackingScope.set('DRAFTS');
      this.refresh.update((n) => n + 1);
      this.activeTab.set('orders');
      if (result.orders.length === 1) {
        this.selectedOrder.set(result.orders[0].id);
      }
    } catch (error) {
      this.toast.error(errorDetail(error, 'Génération refusée'));
    } finally {
      this.generating.set(false);
    }
  }

  protected async associate(line: ProposalLine): Promise<void> {
    const supplierId = this.links[line.productId];
    if (!supplierId) {
      return;
    }
    try {
      await firstValueFrom(
        this.http.put(`${this.api}/suppliers/${supplierId}/products/${line.productId}`, {
          supplierReference: null,
          purchasePriceCents: null,
          packSize: 1,
          preferred: true,
        }),
      );
      const name = this.suppliers().find((s) => s.id === supplierId)?.details.name ?? '';
      this.toast.success(`${line.designation} associé à ${name}`);
      delete this.links[line.productId];
      this.additions.update((list) =>
        list.map((a) => (a.line.productId === line.productId ? { ...a, supplierId } : a)),
      );
      await this.loadProposal(true);
    } catch (error) {
      this.toast.error(errorDetail(error, 'Association refusée'));
    }
  }

  private async init(): Promise<void> {
    try {
      this.suppliers.set(await firstValueFrom(this.http.get<Supplier[]>(`${this.api}/suppliers`)));
    } catch (error) {
      this.toast.error(errorDetail(error, 'Fournisseurs indisponibles'));
    }
    await this.loadProposal();
    if (this.activeTab() === 'proposal') {
      try {
        this.summary.set(
          await firstValueFrom(this.http.get<PurchaseOrderSummary>(`${this.api}/orders/summary`)),
        );
      } catch {
        // the overdue counter on the tab is a hint only
      }
    }
  }
}
