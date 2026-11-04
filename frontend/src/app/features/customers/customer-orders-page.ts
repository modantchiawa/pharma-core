import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  CustomerOrder,
  CustomerOrderKind,
  CustomerOrderStatus,
  CustomerOrderSummary,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { CatalogSyncService } from '../../core/offline/catalog-sync.service';
import { CachedProduct } from '../../core/offline/pos-database';
import { ToastService } from '../../core/ui/toast.service';
import { Icon } from '../../shared/ui/icon';
import { errorDetail } from '../procurement/purchase-orders';

const STATUS: Record<CustomerOrderStatus, { label: string; badge: string }> = {
  OPEN: { label: 'En attente', badge: 'badge-warning' },
  READY: { label: 'Arrivé, réservé', badge: 'badge-success' },
  DELIVERED: { label: 'Remis', badge: '' },
  CANCELLED: { label: 'Annulé', badge: 'badge-danger' },
};

/**
 * What the pharmacy owes its customers: bons (sold and paid while out of stock, handed over on
 * arrival) and special orders (ordered for a customer, paid when collected). A delivery
 * reserves the product for them; a bon is handed over here, a special order at the counter.
 */
@Component({
  selector: 'app-customer-orders-page',
  imports: [DatePipe, FormsModule, Icon, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Bons et commandes clients</h1>
          <p class="page-subtitle">
            Produits vendus en attente de livraison et commandes spéciales : réservés dès la
            réception, le client est prévenu.
          </p>
        </div>
        <button type="button" class="btn btn-secondary" (click)="load()">
          <app-icon name="refresh" [size]="16" />Actualiser
        </button>
      </header>

      @if (summary(); as s) {
        <div class="mb-5 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="checkCircle" [size]="16" /></span>Prêts à
              remettre
            </div>
            <div class="kpi-value">{{ s.ready }}</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="clock" [size]="16" /></span>En attente du
              produit
            </div>
            <div class="kpi-value">{{ s.open }}</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="receipt" [size]="16" /></span>Bons ouverts
            </div>
            <div class="kpi-value">{{ s.vouchersOpen }}</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="boxes" [size]="16" /></span>Commandes spéciales
            </div>
            <div class="kpi-value">{{ s.specialOpen }}</div>
          </div>
        </div>
      }

      <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
        <section class="card min-w-0 overflow-hidden">
          <div class="card-header flex-wrap gap-2">
            <div class="segmented" role="group" aria-label="Filtre">
              <button
                type="button"
                [class.active]="filter() === 'PENDING'"
                (click)="setFilter('PENDING')"
              >
                À servir
              </button>
              <button
                type="button"
                [class.active]="filter() === 'VOUCHER'"
                (click)="setFilter('VOUCHER')"
              >
                Bons
              </button>
              <button
                type="button"
                [class.active]="filter() === 'SPECIAL'"
                (click)="setFilter('SPECIAL')"
              >
                Commandes spéciales
              </button>
              <button type="button" [class.active]="filter() === 'ALL'" (click)="setFilter('ALL')">
                Historique
              </button>
            </div>
          </div>
          @if (error(); as message) {
            <p class="alert alert-danger m-4" role="alert">{{ message }}</p>
          }
          <div class="overflow-x-auto">
            <table class="data-table w-full whitespace-nowrap" data-testid="customer-orders">
              <thead>
                <tr>
                  <th>N°</th>
                  <th>Type</th>
                  <th>Client</th>
                  <th>Produit</th>
                  <th class="num">Qté</th>
                  <th>Statut</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                @for (order of orders(); track order.id) {
                  <tr>
                    <td class="font-mono">{{ order.number }}</td>
                    <td>
                      {{ order.kind === 'VOUCHER' ? 'Bon' : 'Spéciale' }}
                      @if (order.saleTicket) {
                        <span class="block text-xs text-subtle">ticket {{ order.saleTicket }}</span>
                      }
                    </td>
                    <td>
                      <span class="block">{{ order.customerName ?? order.holderName ?? '—' }}</span>
                      @if (order.holderPhone) {
                        <a class="text-xs text-muted" [href]="'tel:' + order.holderPhone">{{
                          order.holderPhone
                        }}</a>
                      }
                    </td>
                    <td class="max-w-64 truncate">{{ order.designation }}</td>
                    <td class="num">{{ order.quantity }}</td>
                    <td>
                      <span class="badge {{ status(order.status).badge }}">{{
                        status(order.status).label
                      }}</span>
                      <span class="block text-xs text-subtle">{{
                        order.readyAt ?? order.createdAt | date: 'dd/MM HH:mm'
                      }}</span>
                    </td>
                    <td class="text-right">
                      @if (order.kind === 'VOUCHER' && order.status === 'READY') {
                        <button
                          type="button"
                          class="btn btn-primary btn-sm"
                          *appHasPermission="'SALE_CREATE'"
                          (click)="deliver(order)"
                        >
                          Remettre
                        </button>
                      }
                      @if (
                        order.kind === 'SPECIAL' &&
                        (order.status === 'OPEN' || order.status === 'READY')
                      ) {
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm"
                          *appHasPermission="'SALE_CREATE'"
                          (click)="cancel(order)"
                        >
                          Annuler
                        </button>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="7" class="py-8 text-center text-muted">Rien à servir.</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="px-4 py-3 text-xs text-muted">
            Une commande spéciale se règle en caisse : sélectionnez le client, ses commandes prêtes
            s'affichent. Un bon déjà payé se remet ici ou en caisse ; pour le rembourser, faites un
            retour du ticket.
          </p>
        </section>

        <aside class="card h-fit" *appHasPermission="'SALE_CREATE'">
          <div class="card-header"><h2 class="card-title">Nouvelle commande spéciale</h2></div>
          <div class="card-body space-y-3">
            <label class="block">
              <span class="label">Produit</span>
              <input
                class="input"
                placeholder="Nom ou code"
                [(ngModel)]="productTerm"
                (ngModelChange)="searchProducts($event)"
              />
            </label>
            @if (products().length > 0) {
              <ul class="max-h-48 divide-y overflow-auto rounded-[var(--radius-sm)] border text-sm">
                @for (product of products(); track product.productId) {
                  <li>
                    <button
                      type="button"
                      class="w-full px-3 py-2 text-left hover:bg-[var(--surface-2)]"
                      (click)="pick(product)"
                    >
                      {{ product.designation }}
                      <span class="text-xs text-muted"
                        >· stock {{ product.availableQuantity }}</span
                      >
                    </button>
                  </li>
                }
              </ul>
            }
            @if (picked(); as product) {
              <p class="text-sm font-medium">{{ product.designation }}</p>
            }
            <label class="block">
              <span class="label">Quantité</span>
              <input type="number" min="1" class="input" [(ngModel)]="quantity" />
            </label>
            <label class="block">
              <span class="label">Nom du client</span>
              <input class="input" [(ngModel)]="holderName" />
            </label>
            <label class="block">
              <span class="label">Téléphone</span>
              <input class="input" type="tel" [(ngModel)]="holderPhone" />
            </label>
            <label class="block">
              <span class="label">Note</span>
              <input class="input" [(ngModel)]="notes" />
            </label>
            <button
              type="button"
              class="btn btn-primary w-full"
              [disabled]="!picked() || !holderPhone.trim() || quantity < 1 || busy()"
              (click)="create()"
            >
              <app-icon name="plus" [size]="16" />Enregistrer la commande
            </button>
            <p class="text-xs text-muted">
              Pour un client enregistré, passez plutôt par la caisse : « Commande spéciale » sur un
              produit en rupture.
            </p>
          </div>
        </aside>
      </div>
    </div>
  `,
})
export class CustomerOrdersPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/customer-orders`;
  private readonly catalog = inject(CatalogSyncService);
  private readonly toasts = inject(ToastService);

  protected readonly filter = signal<'PENDING' | CustomerOrderKind | 'ALL'>('PENDING');
  protected readonly orders = signal<CustomerOrder[]>([]);
  protected readonly summary = signal<CustomerOrderSummary | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly products = signal<CachedProduct[]>([]);
  protected readonly picked = signal<CachedProduct | null>(null);
  protected productTerm = '';
  protected quantity = 1;
  protected holderName = '';
  protected holderPhone = '';
  protected notes = '';

  constructor() {
    void this.load();
  }

  protected status(status: CustomerOrderStatus): { label: string; badge: string } {
    return STATUS[status];
  }

  protected setFilter(filter: 'PENDING' | CustomerOrderKind | 'ALL'): void {
    this.filter.set(filter);
    void this.load();
  }

  protected async load(): Promise<void> {
    const filter = this.filter();
    const params: Record<string, string | number | boolean> = { limit: 300 };
    if (filter === 'PENDING') {
      params['pending'] = true;
    } else if (filter !== 'ALL') {
      params['kind'] = filter;
      params['pending'] = true;
    }
    try {
      this.error.set(null);
      const [orders, summary] = await Promise.all([
        firstValueFrom(this.http.get<CustomerOrder[]>(this.api, { params })),
        firstValueFrom(this.http.get<CustomerOrderSummary>(`${this.api}/summary`)),
      ]);
      this.orders.set(orders);
      this.summary.set(summary);
    } catch (error) {
      this.error.set(errorDetail(error, 'Commandes indisponibles (serveur injoignable)'));
    }
  }

  protected async deliver(order: CustomerOrder): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`${this.api}/${order.id}/delivery`, {}));
      this.toasts.success(
        `Bon n° ${order.number} remis à ${order.customerName ?? order.holderName ?? 'le client'}`,
      );
      await this.load();
    } catch (error) {
      this.error.set(errorDetail(error, 'Remise non enregistrée'));
    }
  }

  protected async cancel(order: CustomerOrder): Promise<void> {
    const reason = prompt(`Motif de l'annulation de la commande n° ${order.number}`);
    if (!reason?.trim()) {
      return;
    }
    try {
      await firstValueFrom(this.http.post(`${this.api}/${order.id}/cancellation`, { reason }));
      await this.load();
    } catch (error) {
      this.error.set(errorDetail(error, 'Annulation refusée'));
    }
  }

  protected async searchProducts(term: string): Promise<void> {
    this.products.set(term.trim().length >= 2 ? (await this.catalog.search(term)).slice(0, 8) : []);
  }

  protected pick(product: CachedProduct): void {
    this.picked.set(product);
    this.products.set([]);
    this.productTerm = product.designation;
  }

  protected async create(): Promise<void> {
    const product = this.picked();
    if (!product) {
      return;
    }
    this.busy.set(true);
    try {
      const order = await firstValueFrom(
        this.http.post<CustomerOrder>(this.api, {
          productId: product.productId,
          quantity: this.quantity,
          holderName: this.holderName.trim() || null,
          holderPhone: this.holderPhone.trim(),
          unitPriceCents: product.unitPriceCents,
          notes: this.notes.trim() || null,
        }),
      );
      this.toasts.success(`Commande spéciale n° ${order.number} enregistrée`);
      this.picked.set(null);
      this.productTerm = '';
      this.quantity = 1;
      this.holderName = '';
      this.holderPhone = '';
      this.notes = '';
      await this.load();
    } catch (error) {
      this.error.set(errorDetail(error, 'Commande non enregistrée'));
    } finally {
      this.busy.set(false);
    }
  }
}
