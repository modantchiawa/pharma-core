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
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
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
import { errorDetail, generationPreview, isoToday, SCOPES } from './purchase-orders';

const REASON_LABELS: Record<string, string> = {
  BELOW_TARGET: 'sous la couverture',
  BELOW_THRESHOLD: 'sous le seuil',
  EXPIRING_REPLACEMENT: 'péremption proche',
  LOST_SALES: 'ventes perdues',
};

const REASON_BADGE: Record<string, string> = {
  BELOW_THRESHOLD: 'badge-warning',
  EXPIRING_REPLACEMENT: 'badge-info',
  LOST_SALES: 'badge-danger',
};

type Tab = 'proposal' | 'orders';

/**
 * Tomorrow's order sheet and the follow-up of purchase orders: the system proposes, the
 * pharmacist adjusts quantities and generates one order per supplier, sends them (WhatsApp,
 * e-mail, print), then tracks deliveries and receives them lot by lot.
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
            Fiche<span class="hidden sm:inline">&nbsp;de commande</span>
          </button>
          <button
            type="button"
            role="tab"
            class="inline-flex items-center gap-1.5 whitespace-nowrap"
            [attr.aria-selected]="activeTab() === 'orders'"
            (click)="activeTab.set('orders')"
          >
            <app-icon name="procurement" [size]="15" />
            Suivi<span class="hidden sm:inline">&nbsp;des commandes</span>
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
                <div class="kpi-foot">{{ selectedCount() }} retenue(s)</div>
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
                        {{ quantities()[line.productId] }} à commander
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

          <section class="card">
            <div class="card-header flex-wrap">
              <div class="flex min-w-0 flex-1 flex-wrap items-center gap-2">
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
                  @for (line of sheet()?.lines ?? []; track line.productId) {
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
                      <td class="num">{{ line.dailyDemand | number: '1.1-1' }}</td>
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
                          <p>Rien à commander : le stock couvre la demande prévue.</p>
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
  protected readonly preview = computed(() =>
    generationPreview(this.sheet()?.lines ?? [], this.quantities(), this.suppliers()),
  );
  protected supplierId: string | null = null;
  /** Supplier chosen for each product that has none yet. */
  protected links: Record<string, string | undefined> = {};

  constructor() {
    void this.init();
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
        }),
      );
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
