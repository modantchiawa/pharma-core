import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  PurchaseOrder,
  PurchaseOrderScope,
  PurchaseOrderSummary,
  Supplier,
} from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { daysLate, errorDetail, isoToday, orderStatus, SCOPES } from './purchase-orders';

/**
 * "Suivi des commandes": key figures, scope and supplier filters, and the orders with their
 * status and delay. Selecting a row opens the order detail (handled by the page).
 */
@Component({
  selector: 'app-order-tracking',
  imports: [FormsModule, DatePipe, MoneyPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-5">
      <section
        class="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-5"
        aria-label="Indicateurs des commandes"
      >
        <button type="button" class="card kpi text-left" (click)="scope.set('DRAFTS')">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="pencil" [size]="16" /></span>
            Brouillons
          </div>
          <div class="kpi-value">{{ summary()?.drafts ?? '—' }}</div>
          <div class="kpi-foot">À vérifier et envoyer</div>
        </button>
        <button type="button" class="card kpi text-left" (click)="scope.set('OPEN')">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="procurement" [size]="16" /></span>
            En cours
          </div>
          <div class="kpi-value">{{ summary()?.open ?? '—' }}</div>
          <div class="kpi-foot">Envoyées, livraison attendue</div>
        </button>
        <button
          type="button"
          class="card kpi text-left"
          [class.kpi-alert]="(summary()?.overdue ?? 0) > 0"
          (click)="scope.set('OVERDUE')"
          data-testid="kpi-overdue"
        >
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="alert" [size]="16" /></span>
            En retard
          </div>
          <div class="kpi-value">{{ summary()?.overdue ?? '—' }}</div>
          <div class="kpi-foot">Date de livraison dépassée</div>
        </button>
        <button type="button" class="card kpi text-left" (click)="scope.set('OPEN')">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
            Attendues aujourd'hui
          </div>
          <div class="kpi-value">{{ summary()?.expectedToday ?? '—' }}</div>
          <div class="kpi-foot">Préparez la réception</div>
        </button>
        <div class="card kpi col-span-2 sm:col-span-1">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>
            Montant en attente
          </div>
          <div class="kpi-value">{{ summary()?.openAmountCents ?? 0 | money }}</div>
          <div class="kpi-foot">Reste à livrer, au prix d'achat</div>
        </div>
      </section>

      <section class="card">
        <div class="card-header flex-wrap gap-3">
          <div
            class="segmented max-w-full overflow-x-auto"
            role="tablist"
            aria-label="Commandes affichées"
          >
            @for (s of scopes; track s.value) {
              <button
                type="button"
                role="tab"
                class="whitespace-nowrap"
                [attr.aria-selected]="scope() === s.value"
                (click)="scope.set(s.value)"
              >
                {{ s.label }}
                @if (s.value === 'OVERDUE' && (summary()?.overdue ?? 0) > 0) {
                  <span class="badge badge-danger ml-1">{{ summary()?.overdue }}</span>
                }
              </button>
            }
          </div>
          <select
            class="select w-full sm:ml-auto sm:w-56"
            aria-label="Filtrer par fournisseur"
            [ngModel]="supplierId()"
            (ngModelChange)="supplierId.set($event)"
          >
            <option [ngValue]="null">Tous les fournisseurs</option>
            @for (s of suppliers(); track s.id) {
              <option [ngValue]="s.id">{{ s.details.name }}</option>
            }
          </select>
        </div>
        <ul class="divide-y divide-line sm:hidden" aria-label="Commandes">
          @for (o of orders(); track o.id) {
            <li>
              <button
                type="button"
                class="w-full px-4 py-3 text-left"
                (click)="selected.emit(o.id)"
                [attr.data-testid]="'order-' + o.number"
              >
                <span class="flex items-start justify-between gap-2">
                  <span class="min-w-0">
                    <span class="block truncate font-medium">{{ o.supplierName }}</span>
                    <span class="block font-mono text-xs text-subtle">{{ o.number }}</span>
                  </span>
                  <span class="num font-medium">{{ o.totalCents | money }}</span>
                </span>
                <span class="mt-1.5 flex flex-wrap items-center gap-1">
                  <span class="badge" [class]="badge(o)"
                    ><span class="dot"></span>{{ label(o) }}</span
                  >
                  @if (o.overdue) {
                    <span class="badge badge-danger">En retard de {{ late(o) }} j</span>
                  }
                  <span class="ml-auto text-xs text-muted">
                    @if (o.expectedAt) {
                      prévue {{ o.expectedAt | date: 'EEE d MMM' }}
                    }
                    @if (o.status !== 'DRAFT') {
                      · {{ o.receivedUnits }}/{{ o.orderedUnits }}
                    }
                  </span>
                </span>
              </button>
            </li>
          } @empty {
            <li class="empty-state">
              <app-icon name="procurement" [size]="22" />
              <p>{{ emptyText() }}</p>
            </li>
          }
        </ul>
        <div class="hidden overflow-x-auto sm:block">
          <table class="data-table">
            <thead class="whitespace-nowrap">
              <tr>
                <th>N°</th>
                <th>Fournisseur</th>
                <th>Statut</th>
                <th>Livraison prévue</th>
                <th class="num text-right">Reçu</th>
                <th class="num text-right">Montant</th>
              </tr>
            </thead>
            <tbody>
              @for (o of orders(); track o.id) {
                <tr
                  class="cursor-pointer"
                  (click)="selected.emit(o.id)"
                  [attr.data-testid]="'order-' + o.number"
                >
                  <td class="whitespace-nowrap">
                    <button
                      type="button"
                      class="font-mono text-xs font-medium text-brand hover:underline"
                      (click)="$event.stopPropagation(); selected.emit(o.id)"
                    >
                      {{ o.number }}
                    </button>
                    <p class="text-xs text-subtle">{{ o.createdAt | date: 'dd/MM HH:mm' }}</p>
                  </td>
                  <td class="min-w-36">{{ o.supplierName }}</td>
                  <td>
                    <div class="flex flex-wrap items-center gap-1">
                      <span class="badge whitespace-nowrap" [class]="badge(o)">
                        <span class="dot"></span>{{ label(o) }}
                      </span>
                      @if (o.overdue) {
                        <span class="badge badge-danger whitespace-nowrap"
                          >En retard de {{ late(o) }} j</span
                        >
                      }
                    </div>
                  </td>
                  <td class="whitespace-nowrap text-muted">
                    {{ o.expectedAt ? (o.expectedAt | date: 'EEE d MMM') : '—' }}
                  </td>
                  <td class="num whitespace-nowrap">
                    @if (o.status === 'DRAFT') {
                      <span class="text-subtle">—</span>
                    } @else {
                      {{ o.receivedUnits }} / {{ o.orderedUnits }}
                    }
                  </td>
                  <td class="num whitespace-nowrap font-medium">{{ o.totalCents | money }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="6">
                    <div class="empty-state">
                      <app-icon name="procurement" [size]="22" />
                      <p>{{ emptyText() }}</p>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  `,
  styles: `
    .kpi-alert {
      border-color: var(--warning);
      background: var(--warning-soft);
    }
    .kpi-alert .kpi-value {
      color: var(--warning);
    }
    button.kpi {
      cursor: pointer;
      transition: border-color 0.15s;
    }
    button.kpi:hover {
      border-color: var(--border-strong);
    }
  `,
})
export class OrderTracking {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/procurement`;
  private readonly toast = inject(ToastService);

  readonly suppliers = input<Supplier[]>([]);
  /** Bumped by the page after an order changed. */
  readonly refresh = input(0);
  readonly scope = model<PurchaseOrderScope>('OPEN');
  readonly selected = output<string>();
  readonly summaryLoaded = output<PurchaseOrderSummary>();

  protected readonly scopes = SCOPES;
  protected readonly supplierId = signal<string | null>(null);
  protected readonly orders = signal<PurchaseOrder[]>([]);
  protected readonly summary = signal<PurchaseOrderSummary | null>(null);

  constructor() {
    effect(() => {
      const scope = this.scope();
      const supplierId = this.supplierId();
      this.refresh();
      untracked(() => void this.load(scope, supplierId));
    });
  }

  protected label(order: PurchaseOrder): string {
    return orderStatus(order).label;
  }

  protected badge(order: PurchaseOrder): string {
    return orderStatus(order).badge;
  }

  protected late(order: PurchaseOrder): number {
    return Math.max(daysLate(order.expectedAt, this.summary()?.today ?? isoToday()), 1);
  }

  protected emptyText(): string {
    switch (this.scope()) {
      case 'OVERDUE':
        return 'Aucune commande en retard';
      case 'DRAFTS':
        return 'Aucun brouillon : générez les commandes depuis la fiche';
      case 'OPEN':
        return 'Aucune livraison attendue';
      default:
        return 'Aucune commande';
    }
  }

  private async load(scope: PurchaseOrderScope, supplierId: string | null): Promise<void> {
    const params: Record<string, string> = { scope };
    if (supplierId) {
      params['supplierId'] = supplierId;
    }
    try {
      const [orders, summary] = await Promise.all([
        firstValueFrom(this.http.get<PurchaseOrder[]>(`${this.api}/orders`, { params })),
        firstValueFrom(this.http.get<PurchaseOrderSummary>(`${this.api}/orders/summary`)),
      ]);
      if (scope !== this.scope() || supplierId !== this.supplierId()) {
        return;
      }
      this.orders.set(orders);
      this.summary.set(summary);
      this.summaryLoaded.emit(summary);
    } catch (error) {
      this.toast.error(errorDetail(error, 'Commandes indisponibles'));
    }
  }
}
