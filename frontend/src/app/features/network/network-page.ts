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
import { Icon } from '../../shared/ui/icon';

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

const LEVEL_BADGE = {
  OUT_OF_STOCK: 'badge-danger',
  LOW: 'badge-warning',
  AVAILABLE: 'badge-success',
} as const;

const TRANSFER_STATUS: Record<string, { label: string; badge: string }> = {
  REQUESTED: { label: 'Demandée', badge: 'badge-info' },
  ACCEPTED: { label: 'Acceptée', badge: 'badge-brand' },
  COMPLETED: { label: 'Remise', badge: 'badge-success' },
  DECLINED: { label: 'Refusée', badge: 'badge-danger' },
  CANCELLED: { label: 'Annulée', badge: '' },
  EXPIRED: { label: 'Expirée', badge: 'badge-warning' },
};

/** Emergency availability across partner pharmacies and inter-pharmacy reservations. */
@Component({
  selector: 'app-network-page',
  imports: [FormsModule, DatePipe, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Pharmacies partenaires</h1>
          <p class="page-subtitle">
            Disponibilité d'urgence chez les confrères du réseau et réservations inter-pharmacies
          </p>
        </div>
      </header>

      <div class="grid items-start gap-5 xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <section class="card min-w-0" *appHasPermission="'NETWORK_STOCK_QUERY'">
          <div class="card-header">
            <div class="flex items-center gap-3">
              <span class="kpi-icon"><app-icon name="network" [size]="16" /></span>
              <div>
                <h2 class="card-title">Disponibilité dans le réseau</h2>
                <p class="text-xs text-muted">Nom commercial, molécule ou CIP</p>
              </div>
            </div>
          </div>
          <div class="card-body space-y-4">
            <form class="relative flex flex-col gap-2 sm:flex-row" (ngSubmit)="whoHas()">
              <div class="relative min-w-0 flex-1">
                <label class="input-with-icon block">
                  <app-icon name="search" [size]="16" />
                  <input
                    type="search"
                    class="input"
                    placeholder="Qui a… (nom, molécule ou CIP)"
                    aria-label="Produit recherché"
                    [(ngModel)]="term"
                    name="term"
                    (ngModelChange)="suggest($event)"
                  />
                </label>
                @if (suggestions().length > 0) {
                  <ul
                    class="mt-1 max-h-72 overflow-auto rounded-[var(--radius)] border border-line bg-surface p-1 sm:absolute sm:inset-x-0 sm:top-full sm:z-10 sm:shadow-[var(--shadow-lg)]"
                  >
                    @for (p of suggestions(); track p.productId) {
                      <li>
                        <button
                          type="button"
                          class="flex w-full items-center gap-2 rounded-[var(--radius-sm)] px-3 py-2 text-left text-sm hover:bg-surface-2"
                          (click)="query(p)"
                        >
                          <app-icon name="pill" [size]="15" class="text-subtle" />
                          {{ p.designation }}
                        </button>
                      </li>
                    }
                  </ul>
                }
              </div>
              <button type="submit" class="btn btn-primary" [disabled]="term.trim().length < 2">
                <app-icon name="search" [size]="16" />
                Qui l'a ?
              </button>
            </form>

            @if (error()) {
              <div class="alert alert-danger" role="alert">
                <app-icon name="error" [size]="16" class="mt-0.5" />
                <span>{{ error() }}</span>
              </div>
            }

            @if (whoHasResults(); as results) {
              <div class="rounded-[var(--radius)] border border-line">
                <p class="border-b border-line bg-surface-2 px-4 py-2.5 text-sm font-medium">
                  {{ results.length }} produit(s) disponible(s) chez des partenaires
                </p>
                <div class="divide-y divide-[var(--border)]">
                  @for (item of results; track item.productId) {
                    <details class="group">
                      <summary
                        class="flex list-none items-center gap-3 px-4 py-3 hover:bg-surface-2 [&::-webkit-details-marker]:hidden"
                      >
                        <app-icon
                          name="chevronRight"
                          [size]="16"
                          class="text-subtle transition-transform group-open:rotate-90"
                        />
                        <span class="min-w-0 flex-1 font-medium">{{ item.designation }}</span>
                        <span class="badge">{{ item.partners.length }} pharmacie(s)</span>
                      </summary>
                      <ul class="space-y-1 px-4 pb-3 pl-11">
                        @for (partner of item.partners; track partner.tenantId) {
                          <li
                            class="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-sm)] bg-surface-2 px-3 py-2 text-sm"
                          >
                            <span class="min-w-0">
                              <span class="font-medium">{{ partner.pharmacyName }}</span>
                              @if (partner.phone) {
                                <span class="ml-2 text-xs text-muted">{{ partner.phone }}</span>
                              }
                            </span>
                            <span class="flex items-center gap-2">
                              <span class="badge" [class]="levelBadge(partner.level)">
                                <span class="dot"></span>{{ label(partner.level) }} ({{
                                  partner.quantity
                                }}{{ partner.capped ? '+' : '' }})
                              </span>
                              <button
                                type="button"
                                class="btn btn-ghost btn-sm"
                                (click)="inspectPartner(partner.tenantId, partner.pharmacyName)"
                              >
                                <app-icon name="eye" [size]="14" />
                                voir son stock
                              </button>
                            </span>
                          </li>
                        }
                      </ul>
                    </details>
                  } @empty {
                    <div class="empty-state py-6">
                      <app-icon name="search" [size]="22" />
                      <p>Aucun partenaire ne déclare ce produit.</p>
                    </div>
                  }
                </div>
              </div>
            }

            @if (partnerName(); as name) {
              <div class="rounded-[var(--radius)] border border-line bg-surface-2 p-4">
                <form
                  class="flex flex-col gap-2 sm:flex-row sm:items-end"
                  (ngSubmit)="loadPartnerStock()"
                >
                  <label class="block min-w-0 flex-1">
                    <span class="label">Stock de {{ name }} pour</span>
                    <span class="input-with-icon block">
                      <app-icon name="search" [size]="16" />
                      <input
                        type="search"
                        class="input"
                        [(ngModel)]="partnerTerm"
                        name="partnerTerm"
                        placeholder="produit ou molécule"
                      />
                    </span>
                  </label>
                  <div class="flex gap-2">
                    <button type="submit" class="btn btn-secondary flex-1">Chercher</button>
                    <button
                      type="button"
                      class="btn btn-ghost flex-1"
                      (click)="partnerName.set(null)"
                    >
                      Fermer
                    </button>
                  </div>
                </form>
                <ul class="mt-3 divide-y divide-[var(--border)] text-sm">
                  @for (item of partnerStock(); track item.productId) {
                    <li class="flex flex-wrap items-center justify-between gap-2 py-2">
                      <span class="min-w-0">
                        <span class="font-medium">{{ item.designation }}</span>
                        <span class="ml-2 text-xs text-subtle">{{ item.dci }}</span>
                      </span>
                      <span class="badge" [class]="levelBadge(item.level)">
                        <span class="dot"></span>{{ label(item.level) }} ({{ item.quantity
                        }}{{ item.capped ? '+' : '' }})
                      </span>
                    </li>
                  } @empty {
                    <li class="py-2 text-muted">Rien en stock pour cette recherche.</li>
                  }
                </ul>
              </div>
            }

            @if (selected(); as product) {
              <div class="rounded-[var(--radius)] border border-line">
                <h3
                  class="flex items-center gap-2 border-b border-line bg-surface-2 px-4 py-2.5 text-sm font-semibold"
                >
                  <app-icon name="pill" [size]="15" class="text-brand" />
                  {{ product.designation }}
                </h3>
                <ul class="divide-y divide-[var(--border)]">
                  @for (partner of partners(); track partner.tenantId) {
                    <li class="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                      <div class="flex min-w-0 items-center gap-3">
                        <span
                          class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-3 text-muted"
                        >
                          <app-icon name="store" [size]="16" />
                        </span>
                        <div class="min-w-0">
                          <p class="truncate font-medium">
                            {{ partner.pharmacyName }}
                            <small class="font-normal text-subtle">{{ partner.city }}</small>
                          </p>
                          <p class="flex flex-wrap items-center gap-x-3 text-xs text-muted">
                            <span class="inline-flex items-center gap-1">
                              <app-icon name="phone" [size]="12" />{{ partner.phone }}
                            </span>
                            <span>{{ partner.networkName }}</span>
                          </p>
                        </div>
                      </div>
                      <div class="flex items-center gap-2">
                        <span class="badge" [class]="levelBadge(partner.level)">
                          <span class="dot"></span>{{ label(partner.level) }} ({{ partner.quantity
                          }}{{ partner.capped ? '+' : '' }})
                        </span>
                        @if (partner.level !== 'OUT_OF_STOCK') {
                          <button
                            *appHasPermission="'NETWORK_TRANSFER_REQUEST'"
                            type="button"
                            class="btn btn-primary btn-sm"
                            (click)="reserve(partner, product)"
                          >
                            Réserver
                          </button>
                        }
                      </div>
                    </li>
                  } @empty {
                    <li class="empty-state py-6">
                      <app-icon name="store" [size]="22" />
                      <p>Aucune pharmacie partenaire ne partage ce produit.</p>
                    </li>
                  }
                </ul>
              </div>
            }

            @if (!whoHasResults() && !selected() && !partnerName()) {
              <div class="empty-state">
                <app-icon name="network" [size]="22" />
                <p class="font-medium text-ink">Un produit manque au comptoir ?</p>
                <p>Cherchez-le : les pharmacies partenaires qui l'ont en stock s'affichent ici.</p>
              </div>
            }
          </div>
        </section>

        <section
          class="card min-w-0"
          *appHasPermission="['NETWORK_TRANSFER_REQUEST', 'NETWORK_TRANSFER_RESPOND']"
        >
          <div class="card-header">
            <div class="flex items-center gap-3">
              <span class="kpi-icon"><app-icon name="transfer" [size]="16" /></span>
              <h2 class="card-title">Réservations inter-pharmacies</h2>
            </div>
            <span class="badge">{{ transfers().length }}</span>
          </div>
          <ul class="divide-y divide-[var(--border)]">
            @for (t of transfers(); track t.id) {
              <li class="flex gap-3 px-5 py-4">
                <span
                  class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full"
                  [class]="
                    incoming(t) ? 'bg-[var(--info-soft)] text-info' : 'bg-brand-soft text-brand'
                  "
                >
                  <app-icon [name]="incoming(t) ? 'trendDownRight' : 'trendUpRight'" [size]="16" />
                </span>
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <p class="text-sm">
                      <strong>{{ t.quantity }}</strong> unité(s) ·
                      {{ incoming(t) ? 'demande reçue' : 'demande envoyée' }}
                    </p>
                    <span class="badge ml-auto" [class]="transferBadge(t.status)">
                      {{ transferLabel(t.status) }}
                    </span>
                  </div>
                  <p class="mt-0.5 text-xs text-muted">
                    {{ t.requestedAt | date: 'dd/MM HH:mm' }} {{ t.requesterNote }}
                    @if (t.reservationExpiresAt) {
                      · réservé jusqu'à {{ t.reservationExpiresAt | date: 'HH:mm' }}
                    }
                  </p>
                  @if (t.status === 'REQUESTED' || t.status === 'ACCEPTED') {
                    <div class="mt-2 flex flex-wrap gap-2">
                      @if (incoming(t) && t.status === 'REQUESTED') {
                        <button
                          type="button"
                          class="btn btn-primary btn-sm"
                          (click)="act(t, 'acceptance')"
                        >
                          <app-icon name="check" [size]="14" />
                          Accepter
                        </button>
                        <button
                          type="button"
                          class="btn btn-secondary btn-sm"
                          (click)="act(t, 'refusal')"
                        >
                          Refuser
                        </button>
                      }
                      @if (incoming(t) && t.status === 'ACCEPTED') {
                        <button
                          type="button"
                          class="btn btn-primary btn-sm"
                          (click)="act(t, 'completion')"
                        >
                          <app-icon name="checkCircle" [size]="14" />
                          Remis
                        </button>
                      }
                      @if (!incoming(t) && (t.status === 'REQUESTED' || t.status === 'ACCEPTED')) {
                        <button
                          type="button"
                          class="btn btn-secondary btn-sm"
                          (click)="act(t, 'cancellation')"
                        >
                          Annuler
                        </button>
                      }
                    </div>
                  }
                </div>
              </li>
            } @empty {
              <li class="empty-state">
                <app-icon name="transfer" [size]="22" />
                <p>Aucune réservation.</p>
              </li>
            }
          </ul>
        </section>
      </div>
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

  protected levelBadge(level: PartnerAvailability['level']): string {
    return LEVEL_BADGE[level];
  }

  protected transferLabel(status: string): string {
    return TRANSFER_STATUS[status]?.label ?? status;
  }

  protected transferBadge(status: string): string {
    return TRANSFER_STATUS[status]?.badge ?? '';
  }

  private async loadTransfers(): Promise<void> {
    try {
      this.transfers.set(await firstValueFrom(this.http.get<Transfer[]>(`${this.api}/transfers`)));
    } catch {
      this.transfers.set([]);
    }
  }
}
