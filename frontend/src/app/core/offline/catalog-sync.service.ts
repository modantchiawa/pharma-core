import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { PosCatalogItem, PosCatalogPage } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { CachedProduct, designationKey } from './pos-database';
import { PosDatabaseService } from './pos-database.service';

const CURSOR_KEY = 'catalogCursor';
const PAGE_SIZE = 500;

interface CatalogCursor {
  since: string;
  after: string | null;
}

/** Incremental pull of the national catalog merged with the pharmacy's prices and stock. */
@Injectable({ providedIn: 'root' })
export class CatalogSyncService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly databases = inject(PosDatabaseService);
  private running: Promise<number> | null = null;

  readonly lastSyncAt = signal<Date | null>(null);

  /** Pulls every change since the stored cursor; concurrent calls share the same run. */
  pull(): Promise<number> {
    this.running ??= this.doPull().finally(() => (this.running = null));
    return this.running;
  }

  async search(term: string, limit = 20): Promise<CachedProduct[]> {
    const products = this.databases.db().products;
    const trimmed = term.trim();
    if (/^\d{13}$/.test(trimmed)) {
      const byCode = await products
        .where('ean13')
        .equals(trimmed)
        .or('cip13')
        .equals(trimmed)
        .toArray();
      return byCode.filter((p) => p.active);
    }
    return products
      .where('designationKey')
      .startsWith(designationKey(trimmed))
      .filter((p) => p.active)
      .limit(limit)
      .toArray();
  }

  private async doPull(): Promise<number> {
    const db = this.databases.db();
    let cursor = ((await db.meta.get(CURSOR_KEY))?.value as CatalogCursor | undefined) ?? {
      since: '1970-01-01T00:00:00Z',
      after: null,
    };
    let total = 0;
    for (;;) {
      let params = new HttpParams().set('since', cursor.since).set('limit', PAGE_SIZE);
      if (cursor.after) {
        params = params.set('after', cursor.after);
      }
      const page = await firstValueFrom(
        this.http.get<PosCatalogPage>(`${this.env.apiUrl}/v1/pos/catalog`, { params }),
      );
      const next: CatalogCursor = { since: page.nextSince, after: page.nextAfter };
      await db.transaction('rw', db.products, db.meta, async () => {
        await db.products.bulkPut(page.items.map(toCachedProduct));
        await db.meta.put({ key: CURSOR_KEY, value: next });
      });
      total += page.items.length;
      cursor = next;
      if (!page.hasMore) {
        break;
      }
    }
    this.lastSyncAt.set(new Date());
    return total;
  }
}

export function toCachedProduct(item: PosCatalogItem): CachedProduct {
  return {
    productId: item.productId,
    cip13: item.cip13,
    ean13: item.ean13,
    designation: item.designation,
    designationKey: designationKey(item.designation),
    dci: item.dci,
    listing: item.listing,
    vatRate: item.vatRate,
    unitPriceCents: item.unitPriceCents,
    availableQuantity: item.availableQuantity,
    active: item.active,
    changedAt: item.changedAt,
  };
}
