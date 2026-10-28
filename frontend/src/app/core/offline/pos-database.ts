import Dexie, { Table } from 'dexie';
import { DrugListing, SaleOutcome, SaleRequest, StorageLocationKind } from '../api/models';

export interface CachedProduct {
  productId: string;
  cip13: string;
  ean13: string | null;
  designation: string;
  designationKey: string;
  dci: string | null;
  /** ATC class, absent from entries cached before the counter vigilance existed. */
  atcCode?: string | null;
  listing: DrugListing;
  vatRate: number;
  unitPriceCents: number | null;
  availableQuantity: number;
  active: boolean;
  /**
   * Where the product is stored. Not indexed, so no schema version is needed; absent from
   * entries cached before locations existed, until the product changes again.
   */
  locationCode?: string | null;
  locationName?: string | null;
  locationKind?: StorageLocationKind | null;
  locationDetail?: string | null;
  changedAt: string;
  /** Version of the product photo, absent from entries cached before photos existed. */
  imageVersion?: string | null;
}

export type OutboxStatus = 'PENDING' | 'SYNCED' | 'REJECTED';

/** A completed sale waiting to be (or already) acknowledged by the server. */
export interface OutboxSale {
  clientSaleId: string;
  ticketNumber: string;
  payload: SaleRequest;
  status: OutboxStatus;
  attempts: number;
  nextAttemptAt: number;
  createdAt: number;
  syncedAt?: number;
  lastError?: string;
  outcome?: SaleOutcome;
}

/** A line of the interaction referential, kept locally so the counter warns offline too. */
export interface CachedSafetyRule {
  id: string;
  kind: 'INTERACTION' | 'CONTEXT';
  substance: string;
  counterpart: string;
  severity: 'CONTRAINDICATED' | 'ADVISED_AGAINST' | 'PRECAUTION';
  advice: string;
  updatedAt: string;
}

export interface MetaEntry {
  key: string;
  value: unknown;
}

/**
 * Local store of one pharmacy on one terminal. The database name contains the tenant id so
 * that two pharmacies sharing a device never see each other's journal.
 */
export class PosDatabase extends Dexie {
  products!: Table<CachedProduct, string>;
  outbox!: Table<OutboxSale, string>;
  meta!: Table<MetaEntry, string>;
  safetyRules!: Table<CachedSafetyRule, string>;

  constructor(tenantId: string) {
    super(`pharmacore-pos-${tenantId}`);
    this.version(1).stores({
      products: 'productId, cip13, ean13, designationKey, changedAt',
      outbox: 'clientSaleId, status, [status+nextAttemptAt], createdAt',
      meta: 'key',
    });
    // v2: the interaction referential, for the counter vigilance without the server
    this.version(2).stores({
      products: 'productId, cip13, ean13, designationKey, changedAt',
      outbox: 'clientSaleId, status, [status+nextAttemptAt], createdAt',
      meta: 'key',
      safetyRules: 'id, substance, counterpart, updatedAt',
    });
  }
}

export function designationKey(designation: string): string {
  return designation
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toUpperCase();
}
