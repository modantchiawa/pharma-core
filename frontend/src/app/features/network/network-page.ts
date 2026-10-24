import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { NodeInfoService } from '../../core/api/node-info.service';
import { CatalogSyncService } from '../../core/offline/catalog-sync.service';
import { CachedProduct } from '../../core/offline/pos-database';
import { StompService } from '../../core/realtime/stomp.service';

interface PartnerAvailability {
  tenantId: string;
  pharmacyName: string;
  city: string | null;
  phone: string | null;
  networkId: string;
  networkName: string;
  level: 'OUT_OF_STOCK' | 'LOW' | 'AVAILABLE';
  quantity: number;
  capped: boolean;
}

interface Transfer {
  id: string;
  networkId: string;
  requesterTenantId: string;
  providerTenantId: string;
  productId: string;
  quantity: number;
  status: string;
  requesterNote: string | null;
  providerNote: string | null;
  requestedAt: string;
  reservationExpiresAt: string | null;
}

const LEVEL_LABEL = {
  OUT_OF_STOCK: 'Rupture',
  LOW: 'Stock faible',
  AVAILABLE: 'Disponible',
} as const;

/** Emergency availability across partner pharmacies and inter-pharmacy reservations. */
@Component({
  selector: 'app-network-page',
  imports: [FormsModule, DatePipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-4 p-4 xl:grid-cols-2">
      <section
        class="space-y-3 rounded-lg border bg-white p-4"
        *appHasPermission="'NETWORK_STOCK_QUERY'"
      >
        <h2 class="text-lg font-semibold">Disponibilité dans le réseau</h2>
        <input
          type="search"
          class="w-full rounded border px-3 py-2"
          placeholder="Produit recherché"
          [(ngModel)]="term"
          (ngModelChange)="suggest($event)"
        />
        <ul>
          @for (p of suggestions(); track p.productId) {
            <li>
              <button
                type="button"
                class="w-full px-2 py-1 text-left hover:bg-slate-100"
                (click)="query(p)"
              >
                {{ p.designation }}
              </button>
            </li>
          }
        </ul>
        @if (selected(); as product) {
          <h3 class="font-medium">{{ product.designation }}</h3>
          @if (error()) {
            <p class="text-red-700" role="alert">{{ error() }}</p>
          }
          <ul class="divide-y">
            @for (partner of partners(); track partner.tenantId) {
              <li class="flex items-center justify-between py-2">
                <div>
                  <p class="font-medium">
                    {{ partner.pharmacyName }}
                    <small class="text-slate-500">{{ partner.city }}</small>
                  </p>
                  <p class="text-sm text-slate-600">
                    {{ partner.phone }} · {{ partner.networkName }}
                  </p>
                </div>
                <div class="text-right">
                  <span
                    class="rounded px-2 py-0.5 text-sm"
                    [class.bg-green-100]="partner.level === 'AVAILABLE'"
                    [class.bg-amber-100]="partner.level === 'LOW'"
                    [class.bg-slate-200]="partner.level === 'OUT_OF_STOCK'"
                  >
                    {{ label(partner.level) }} ({{ partner.quantity
                    }}{{ partner.capped ? '+' : '' }})
                  </span>
                  @if (partner.level !== 'OUT_OF_STOCK') {
                    <button
                      *appHasPermission="'NETWORK_TRANSFER_REQUEST'"
                      type="button"
                      class="ml-2 rounded bg-brand px-2 py-1 text-sm text-on-brand"
                      (click)="reserve(partner, product)"
                    >
                      Réserver
                    </button>
                  }
                </div>
              </li>
            } @empty {
              <li class="py-2 text-slate-500">
                Aucune pharmacie partenaire ne partage ce produit.
              </li>
            }
          </ul>
        }
      </section>

      <section
        class="space-y-3 rounded-lg border bg-white p-4"
        *appHasPermission="['NETWORK_TRANSFER_REQUEST', 'NETWORK_TRANSFER_RESPOND']"
      >
        <h2 class="text-lg font-semibold">Réservations inter-pharmacies</h2>
        <ul class="divide-y">
          @for (t of transfers(); track t.id) {
            <li class="py-2">
              <p>
                <strong>{{ t.quantity }}</strong> unité(s) ·
                {{ incoming(t) ? 'demande reçue' : 'demande envoyée' }} ·
                <span class="font-mono text-sm">{{ t.status }}</span>
              </p>
              <p class="text-sm text-slate-600">
                {{ t.requestedAt | date: 'dd/MM HH:mm' }} {{ t.requesterNote }}
                @if (t.reservationExpiresAt) {
                  · réservé jusqu'à {{ t.reservationExpiresAt | date: 'HH:mm' }}
                }
              </p>
              <div class="mt-1 flex gap-2 text-sm">
                @if (incoming(t) && t.status === 'REQUESTED') {
                  <button
                    type="button"
                    class="rounded bg-brand px-2 py-1 text-on-brand"
                    (click)="act(t, 'acceptance')"
                  >
                    Accepter
                  </button>
                  <button
                    type="button"
                    class="rounded border px-2 py-1"
                    (click)="act(t, 'refusal')"
                  >
                    Refuser
                  </button>
                }
                @if (incoming(t) && t.status === 'ACCEPTED') {
                  <button
                    type="button"
                    class="rounded bg-brand px-2 py-1 text-on-brand"
                    (click)="act(t, 'completion')"
                  >
                    Remis
                  </button>
                }
                @if (!incoming(t) && (t.status === 'REQUESTED' || t.status === 'ACCEPTED')) {
                  <button
                    type="button"
                    class="rounded border px-2 py-1"
                    (click)="act(t, 'cancellation')"
                  >
                    Annuler
                  </button>
                }
              </div>
            </li>
          } @empty {
            <li class="py-2 text-slate-500">Aucune réservation.</li>
          }
        </ul>
      </section>
    </div>
  `,
})
export class NetworkPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(NodeInfoService).networkApiUrl()}/v1/network`;
  private readonly catalog = inject(CatalogSyncService);
  private readonly auth = inject(AuthService);

  protected term = '';
  protected readonly suggestions = signal<CachedProduct[]>([]);
  protected readonly selected = signal<CachedProduct | null>(null);
  protected readonly partners = signal<PartnerAvailability[]>([]);
  protected readonly transfers = signal<Transfer[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.loadTransfers();
    const tenant = this.auth.tenantId();
    if (tenant) {
      inject(StompService)
        .watch(`/topic/tenants.${tenant}.transfers`)
        .pipe(takeUntilDestroyed(inject(DestroyRef)))
        .subscribe(() => void this.loadTransfers());
    }
  }

  protected async suggest(term: string): Promise<void> {
    this.suggestions.set(term.trim().length >= 2 ? await this.catalog.search(term, 8) : []);
  }

  protected async query(product: CachedProduct): Promise<void> {
    this.selected.set(product);
    this.suggestions.set([]);
    this.error.set(null);
    try {
      this.partners.set(
        await firstValueFrom(
          this.http.get<PartnerAvailability[]>(`${this.api}/stock/${product.productId}`),
        ),
      );
    } catch {
      this.error.set('Recherche impossible (limite de requêtes atteinte ou hors-ligne)');
      this.partners.set([]);
    }
  }

  protected async reserve(partner: PartnerAvailability, product: CachedProduct): Promise<void> {
    const quantity = Number(prompt(`Quantité à réserver chez ${partner.pharmacyName}`, '1'));
    if (!Number.isInteger(quantity) || quantity <= 0) {
      return;
    }
    await firstValueFrom(
      this.http.post(`${this.api}/transfers`, {
        networkId: partner.networkId,
        providerTenantId: partner.tenantId,
        productId: product.productId,
        quantity,
        note: prompt('Message pour la pharmacie (optionnel)') ?? undefined,
      }),
    );
    await this.loadTransfers();
  }

  protected async act(
    transfer: Transfer,
    action: 'acceptance' | 'refusal' | 'completion' | 'cancellation',
  ): Promise<void> {
    const body =
      action === 'acceptance' || action === 'refusal'
        ? { note: prompt('Message (optionnel)') ?? undefined }
        : {};
    await firstValueFrom(this.http.post(`${this.api}/transfers/${transfer.id}/${action}`, body));
    await this.loadTransfers();
  }

  protected incoming(transfer: Transfer): boolean {
    return transfer.providerTenantId === this.auth.tenantId();
  }

  protected label(level: PartnerAvailability['level']): string {
    return LEVEL_LABEL[level];
  }

  private async loadTransfers(): Promise<void> {
    try {
      this.transfers.set(await firstValueFrom(this.http.get<Transfer[]>(`${this.api}/transfers`)));
    } catch {
      this.transfers.set([]);
    }
  }
}
