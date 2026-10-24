import { DatePipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ExpiryWriteOff, StockIntegrityReport } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { parseGs1 } from '../../shared/gs1';
import { MoneyPipe } from '../../shared/money.pipe';

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
}

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
  imports: [FormsModule, MoneyPipe, DatePipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <nav class="flex gap-2" role="tablist">
        @for (t of tabs; track t.id) {
          <button
            type="button"
            role="tab"
            class="rounded px-3 py-1"
            [attr.aria-selected]="tab() === t.id"
            [class.bg-brand]="tab() === t.id"
            [class.text-on-brand]="tab() === t.id"
            (click)="open(t.id)"
          >
            {{ t.label }}
          </button>
        }
      </nav>

      @switch (tab()) {
        @case ('stocks') {
          <form
            class="flex flex-wrap items-end gap-2 rounded-lg border bg-white p-3"
            (ngSubmit)="receive()"
            *appHasPermission="'STOCK_RECEIVE'"
          >
            <label class="flex-1 text-sm"
              >Réception rapide : scan DataMatrix GS1 (lot et péremption pré-remplis) ou EAN
              <input
                class="field"
                [(ngModel)]="receipt.scan"
                name="scan"
                (keydown.enter)="applyScan($event)"
                placeholder="Scanner la boîte"
              />
            </label>
            <label class="text-sm"
              >Lot<input
                class="field"
                [(ngModel)]="receipt.lotNumber"
                name="lot"
                required
                maxlength="40"
            /></label>
            <label class="text-sm"
              >Péremption<input
                type="date"
                class="field"
                [(ngModel)]="receipt.expiryDate"
                name="expiry"
                required
            /></label>
            <label class="text-sm"
              >Qté<input
                type="number"
                min="1"
                class="field w-20"
                [(ngModel)]="receipt.quantity"
                name="qty"
                required
            /></label>
            <label class="text-sm"
              >Coût unitaire<input
                inputmode="decimal"
                class="field w-28"
                [(ngModel)]="receipt.unitCost"
                name="cost"
                required
            /></label>
            <button
              type="submit"
              class="rounded bg-brand px-3 py-2 text-on-brand"
              [disabled]="!receipt.productId"
            >
              Entrer en stock
            </button>
            @if (receipt.designation) {
              <p class="w-full text-sm text-slate-600">Produit : {{ receipt.designation }}</p>
            }
          </form>
          <div class="flex gap-2">
            <input
              type="search"
              class="flex-1 rounded border px-3 py-2"
              placeholder="Désignation ou CIP13"
              [(ngModel)]="query"
              (keydown.enter)="loadStocks()"
            />
            <label class="flex items-center gap-1"
              ><input type="checkbox" [(ngModel)]="belowOnly" (change)="loadStocks()" /> Sous le
              seuil</label
            >
          </div>
          <table class="w-full rounded border bg-white text-sm">
            <thead class="bg-slate-100 text-left">
              <tr>
                <th class="p-2">Produit</th>
                <th class="p-2">Prix</th>
                <th class="p-2">Disponible</th>
                <th class="p-2">Réservé</th>
                <th class="p-2">Écart hors-ligne</th>
                <th class="p-2">Prochaine péremption</th>
              </tr>
            </thead>
            <tbody>
              @for (s of stocks(); track s.productId) {
                <tr class="border-t" [class.bg-amber-50]="s.belowThreshold">
                  <td class="p-2">
                    {{ s.designation }} <small class="text-slate-500">{{ s.cip13 }}</small>
                  </td>
                  <td class="p-2">{{ s.sellingPriceCents ?? s.publicPriceCents | money }}</td>
                  <td class="p-2 font-semibold">{{ s.available }} / {{ s.onHand }}</td>
                  <td class="p-2">{{ s.reserved }}</td>
                  <td class="p-2">
                    @if (s.shortfall > 0) {
                      <span class="text-red-700">{{ s.shortfall }}</span>
                      <button
                        *appHasPermission="'STOCK_ADJUST'"
                        type="button"
                        class="ml-2 underline"
                        (click)="resolve(s)"
                      >
                        Régulariser
                      </button>
                    }
                  </td>
                  <td class="p-2">{{ s.nextExpiry | date: 'dd/MM/yyyy' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        @case ('expiry') {
          <table class="w-full rounded border bg-white text-sm">
            <thead class="bg-slate-100 text-left">
              <tr>
                <th class="p-2">Produit</th>
                <th class="p-2">Lot</th>
                <th class="p-2">Péremption</th>
                <th class="p-2">Qté</th>
              </tr>
            </thead>
            <tbody>
              @for (a of expiry(); track a.lotId) {
                <tr class="border-t" [class.bg-red-50]="a.expired">
                  <td class="p-2">{{ a.designation }}</td>
                  <td class="p-2">{{ a.lotNumber }}</td>
                  <td class="p-2">
                    {{ a.expiryDate | date: 'dd/MM/yyyy' }}
                    @if (a.expired) {
                      <strong class="text-red-700">périmé</strong>
                    }
                  </td>
                  <td class="p-2">{{ a.quantity }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        @case ('integrity') {
          <div class="flex items-center gap-2">
            <p class="text-sm text-slate-600">
              Chaque unité en rayon est expliquée par un mouvement ; un écart signale une altération
              hors application, à régulariser par inventaire.
            </p>
            <button
              *appHasPermission="'STOCK_ADJUST'"
              type="button"
              class="ml-auto rounded bg-brand px-3 py-1 text-on-brand"
              (click)="runIntegrity()"
            >
              Contrôler maintenant
            </button>
          </div>
          @for (r of integrity(); track r.id) {
            <div
              class="rounded border bg-white p-3 text-sm"
              [class.border-red-400]="r.mismatches > 0"
            >
              <p>
                {{ r.runAt | date: 'dd/MM/yyyy HH:mm' }} · {{ r.lotsChecked }} lots contrôlés ·
                <strong [class.text-red-700]="r.mismatches > 0">{{ r.mismatches }} écart(s)</strong>
              </p>
              @if (r.details.length > 0) {
                <table class="mt-2 w-full">
                  <thead class="text-left text-slate-500">
                    <tr>
                      <th>Type</th>
                      <th>Produit</th>
                      <th>Lot</th>
                      <th class="text-right">Stocké</th>
                      <th class="text-right">Calculé</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (d of r.details; track $index) {
                      <tr class="border-t">
                        <td>
                          {{ d.kind === 'LOT_QUANTITY' ? 'Quantité de lot' : 'Réservations' }}
                        </td>
                        <td>{{ d.designation }}</td>
                        <td>{{ d.lotNumber ?? '—' }}</td>
                        <td class="text-right">{{ d.stored }}</td>
                        <td class="text-right">{{ d.computed }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
            </div>
          } @empty {
            <p class="text-sm text-slate-500">Aucun contrôle enregistré.</p>
          }
        }
        @case ('writeoffs') {
          <div class="flex items-center gap-2">
            <p class="text-sm text-slate-600">
              Lots périmés sortis du stock (automatiquement chaque nuit), valorisés au coût d'achat.
            </p>
            <button
              *appHasPermission="'STOCK_ADJUST'"
              type="button"
              class="ml-auto rounded border px-3 py-1"
              (click)="runWriteOff()"
            >
              Sortir les périmés maintenant
            </button>
          </div>
          <table class="w-full rounded border bg-white text-sm">
            <thead class="bg-slate-100 text-left">
              <tr>
                <th class="p-2">Date</th>
                <th class="p-2">Produit</th>
                <th class="p-2">Lot</th>
                <th class="p-2">Péremption</th>
                <th class="p-2 text-right">Qté</th>
                <th class="p-2 text-right">Valeur</th>
              </tr>
            </thead>
            <tbody>
              @for (w of writeOffs(); track w.lotId + w.writtenOffAt) {
                <tr class="border-t">
                  <td class="p-2">{{ w.writtenOffAt | date: 'dd/MM/yyyy' }}</td>
                  <td class="p-2">{{ w.designation }}</td>
                  <td class="p-2">{{ w.lotNumber }}</td>
                  <td class="p-2">{{ w.expiryDate | date: 'dd/MM/yyyy' }}</td>
                  <td class="p-2 text-right">{{ w.quantity }}</td>
                  <td class="p-2 text-right">{{ w.valueCents | money }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="6" class="p-4 text-center text-slate-500">
                    Aucune sortie sur 90 jours
                  </td>
                </tr>
              }
              @if (writeOffs().length > 0) {
                <tr class="border-t font-semibold">
                  <td class="p-2" colspan="5">Total</td>
                  <td class="p-2 text-right">{{ writeOffTotal() | money }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        @case ('reorder') {
          <table class="w-full rounded border bg-white text-sm">
            <thead class="bg-slate-100 text-left">
              <tr>
                <th class="p-2">Produit</th>
                <th class="p-2">Disponible</th>
                <th class="p-2">À commander</th>
              </tr>
            </thead>
            <tbody>
              @for (r of reorder(); track r.productId) {
                <tr class="border-t">
                  <td class="p-2">{{ r.designation }}</td>
                  <td class="p-2">{{ r.available }}</td>
                  <td class="p-2 font-semibold">{{ r.suggestedQuantity }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
      @if (status(); as text) {
        <p class="text-sm" role="status">{{ text }}</p>
      }
    </div>
  `,
  styles: `
    .field {
      display: block;
      width: 100%;
      margin-top: 0.25rem;
      border: 1px solid #cbd5e1;
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
  `,
})
export class InventoryPage {
  private readonly http = inject(HttpClient);
  private readonly currency = inject(CurrencyService);
  private readonly apiBase = inject(APP_ENVIRONMENT).apiUrl;
  private readonly api = `${this.apiBase}/v1/inventory`;

  protected readonly tabs: { id: Tab; label: string }[] = [
    { id: 'stocks', label: 'Stocks' },
    { id: 'expiry', label: 'Péremptions (90 j)' },
    { id: 'writeoffs', label: 'Sorties périmés' },
    { id: 'reorder', label: 'Réapprovisionnement' },
    { id: 'integrity', label: 'Intégrité' },
  ];
  protected readonly tab = signal<Tab>('stocks');
  protected readonly stocks = signal<StockOverview[]>([]);
  protected readonly expiry = signal<ExpiryAlert[]>([]);
  protected readonly reorder = signal<ReorderSuggestion[]>([]);
  protected readonly integrity = signal<StockIntegrityReport[]>([]);
  protected readonly writeOffs = signal<ExpiryWriteOff[]>([]);
  protected readonly status = signal<string | null>(null);
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
    const params = new HttpParams().set('q', this.query).set('belowThreshold', this.belowOnly);
    this.stocks.set(
      await firstValueFrom(this.http.get<StockOverview[]>(`${this.api}/stocks`, { params })),
    );
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
