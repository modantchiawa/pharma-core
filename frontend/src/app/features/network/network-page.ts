import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PartnerProduct, ProductPartners } from '../../core/api/models';
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
        <form class="flex gap-2" (ngSubmit)="whoHas()">
          <input
            type="search"
            class="w-full rounded border px-3 py-2"
            placeholder="Qui a… (nom, molécule ou CIP)"
            [(ngModel)]="term"
            name="term"
            (ngModelChange)="suggest($event)"
          />
          <button
            type="submit"
            class="rounded border px-3 py-2"
            [disabled]="term.trim().length < 2"
          >
            Qui l'a ?
          </button>
        </form>
        @if (whoHasResults(); as results) {
          <div class="rounded border bg-slate-50 p-2">
            <p class="mb-1 text-sm font-medium">
              {{ results.length }} produit(s) disponible(s) chez des partenaires
            </p>
            @for (item of results; track item.productId) {
              <details class="py-1">
                <summary class="cursor-pointer">
                  {{ item.designation }}
                  <small class="text-slate-500">{{ item.partners.length }} pharmacie(s)</small>
                </summary>
                <ul class="ml-4 text-sm">
                  @for (partner of item.partners; track partner.tenantId) {
                    <li class="flex items-center justify-between py-0.5">
                      <span>
                        {{ partner.pharmacyName }} <small>{{ partner.phone }}</small>
                      </span>
                      <span>
                        {{ label(partner.level) }} ({{ partner.quantity
                        }}{{ partner.capped ? '+' : '' }})
                        <button
                          type="button"
                          class="ml-2 underline"
                          (click)="inspectPartner(partner.tenantId, partner.pharmacyName)"
                        >
                          voir son stock
                        </button>
                      </span>
                    </li>
                  }
                </ul>
              </details>
            } @empty {
              <p class="text-sm text-slate-500">Aucun partenaire ne déclare ce produit.</p>
            }
          </div>
        }
        @if (partnerName(); as name) {
          <div class="rounded border bg-white p-2">
            <form class="flex items-end gap-2" (ngSubmit)="loadPartnerStock()">
              <label class="flex-1 text-sm"
                >Stock de {{ name }} pour
                <input
                  type="search"
                  class="mt-1 w-full rounded border px-3 py-1"
                  [(ngModel)]="partnerTerm"
                  name="partnerTerm"
                  placeholder="produit ou molécule"
                />
              </label>
              <button type="submit" class="rounded border px-3 py-1">Chercher</button>
              <button
                type="button"
                class="rounded border px-3 py-1"
                (click)="partnerName.set(null)"
              >
                Fermer
              </button>
            </form>
            <ul class="mt-2 divide-y text-sm">
              @for (item of partnerStock(); track item.productId) {
                <li class="flex justify-between py-1">
                  <span
                    >{{ item.designation }}
                    <small class="text-slate-500">{{ item.dci }}</small></span
                  >
                  <span
                    >{{ label(item.level) }} ({{ item.quantity }}{{ item.capped ? '+' : '' }})</span
                  >
                </li>
              } @empty {
                <li class="py-1 text-slate-500">Rien en stock pour cette recherche.</li>
              }
            </ul>
          </div>
        }
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
  protected partnerTerm = '';
  protected readonly whoHasResults = signal<ProductPartners[] | null>(null);
  protected readonly partnerName = signal<string | null>(null);
  protected readonly partnerStock = signal<PartnerProduct[]>([]);
  private partnerTenant: string | null = null;
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

  /** Across every partner at once: the matching products someone has, from the cloud. */
  protected async whoHas(): Promise<void> {
    const q = this.term.trim();
    if (q.length < 2) {
      return;
    }
    this.suggestions.set([]);
    this.error.set(null);
    try {
      this.whoHasResults.set(
        await firstValueFrom(
          this.http.get<ProductPartners[]>(`${this.api}/stock`, { params: { q } }),
        ),
      );
    } catch {
      this.error.set('Recherche impossible (limite de requêtes atteinte ou hors-ligne)');
      this.whoHasResults.set([]);
    }
  }

  protected inspectPartner(tenantId: string, name: string): void {
    this.partnerTenant = tenantId;
    this.partnerName.set(name);
    this.partnerTerm = this.term.trim();
    void this.loadPartnerStock();
  }

  protected async loadPartnerStock(): Promise<void> {
    const q = this.partnerTerm.trim();
    if (!this.partnerTenant || q.length < 2) {
      return;
    }
    try {
      this.partnerStock.set(
        await firstValueFrom(
          this.http.get<PartnerProduct[]>(`${this.api}/pharmacies/${this.partnerTenant}/stock`, {
            params: { q },
          }),
        ),
      );
    } catch {
      this.error.set('Recherche impossible (limite de requêtes atteinte ou hors-ligne)');
      this.partnerStock.set([]);
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
