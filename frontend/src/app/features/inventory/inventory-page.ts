import { DatePipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
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

type Tab = 'stocks' | 'expiry' | 'reorder';

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
    </div>
  `,
})
export class InventoryPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/inventory`;

  protected readonly tabs: { id: Tab; label: string }[] = [
    { id: 'stocks', label: 'Stocks' },
    { id: 'expiry', label: 'Péremptions (90 j)' },
    { id: 'reorder', label: 'Réapprovisionnement' },
  ];
  protected readonly tab = signal<Tab>('stocks');
  protected readonly stocks = signal<StockOverview[]>([]);
  protected readonly expiry = signal<ExpiryAlert[]>([]);
  protected readonly reorder = signal<ReorderSuggestion[]>([]);
  protected query = '';
  protected belowOnly = false;

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
