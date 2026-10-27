import 'fake-indexeddb/auto';
import Dexie from 'dexie';
import { IDBFactory, IDBKeyRange } from 'fake-indexeddb';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { APP_ENVIRONMENT } from '../config';
import { CatalogSyncService, toCachedProduct } from './catalog-sync.service';
import { CachedProduct, designationKey } from './pos-database';
import { PosDatabaseService } from './pos-database.service';

function product(
  id: string,
  designation: string,
  dci: string | null,
  available: number,
): CachedProduct {
  return {
    productId: id,
    cip13: `34009${id.padStart(8, '0')}`,
    ean13: null,
    designation,
    designationKey: designationKey(designation),
    dci,
    listing: 'NONE',
    vatRate: 0,
    unitPriceCents: 500,
    availableQuantity: available,
    active: true,
    changedAt: '2026-10-01T00:00:00Z',
  };
}

// The specs share one module graph: Dexie must be pointed at a fresh in-memory IndexedDB
// per file, whatever the order the files are evaluated in.
beforeAll(() => {
  Dexie.dependencies.indexedDB = new IDBFactory();
  Dexie.dependencies.IDBKeyRange = IDBKeyRange;
});

describe('CatalogSyncService substitutes', () => {
  let catalog: CatalogSyncService;
  let databases: PosDatabaseService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_ENVIRONMENT, useValue: { apiUrl: 'http://api.test/api' } },
        {
          provide: AuthService,
          useValue: { tenantId: signal(`t-${Math.random().toString(36).slice(2)}`) },
        },
        { provide: PermissionService, useValue: { profile: signal(null) } },
      ],
    });
    catalog = TestBed.inject(CatalogSyncService);
    databases = TestBed.inject(PosDatabaseService);
    await databases
      .db()
      .products.bulkPut([
        product('1', 'DOLIPRANE 500MG', 'Paracetamol', 0),
        product('2', 'PARACETAMOL BIOGARAN 500MG', 'PARACETAMOL', 12),
        product('3', 'EFFERALGAN 500MG', 'paracetamol ', 3),
        product('4', 'AMOXICILLINE 1G', 'AMOXICILLINE', 9),
        { ...product('5', 'PARACETAMOL RETIRE', 'PARACETAMOL', 40), active: false },
      ]);
  });

  it('lists the other products with the same DCI, in stock first, offline', async () => {
    const brand = (await databases.db().products.get('1')) as CachedProduct;
    const substitutes = await catalog.alternatives(brand);
    expect(substitutes.map((p) => p.productId)).toEqual(['2', '3']);
  });

  it('has nothing to offer for a product without DCI', async () => {
    const substitutes = await catalog.alternatives(product('9', 'INCONNU', null, 0));
    expect(substitutes).toEqual([]);
  });
});

describe('toCachedProduct', () => {
  it('keeps the location of the product for the offline counter', () => {
    const cached = toCachedProduct({
      productId: 'p-1',
      cip13: '3400930000001',
      ean13: null,
      designation: 'Insulatard 100UI/ml',
      dci: 'INSULINE',
      listing: 'LIST_I',
      vatRate: 0,
      unitPriceCents: 9_500,
      availableQuantity: 4,
      active: true,
      locationCode: 'FRIGO',
      locationName: 'Réfrigérateur 2-8 °C',
      locationKind: 'FRIDGE',
      locationDetail: 'clayette 1',
      changedAt: '2026-10-27T00:00:00Z',
    });

    expect(cached.designationKey).toBe('INSULATARD 100UI/ML');
    expect([cached.locationCode, cached.locationKind, cached.locationDetail]).toEqual([
      'FRIGO',
      'FRIDGE',
      'clayette 1',
    ]);
  });
});
