import 'fake-indexeddb/auto';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SaleRequest, SyncResponse } from '../api/models';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../auth/permission.service';
import { APP_ENVIRONMENT } from '../config';
import { PosDatabaseService } from './pos-database.service';
import { SaleOutboxService, backoffDelay } from './sale-outbox.service';

const API = 'http://api.test/api';

function sale(id: string, productId = 'p-1', quantity = 2): SaleRequest {
  return {
    clientSaleId: id,
    terminalId: 'T1',
    ticketNumber: `T1-${id}`,
    occurredAt: '2026-10-18T09:00:00Z',
    cashierUserId: 'u-1',
    lines: [{ productId, quantity, unitPriceCents: 250, discountCents: 0 }],
    payments: [{ method: 'CASH', amountCents: 250 * quantity }],
  };
}

describe('SaleOutboxService', () => {
  let outbox: SaleOutboxService;
  let databases: PosDatabaseService;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_ENVIRONMENT, useValue: { apiUrl: API } },
        { provide: AuthService, useValue: { tenantId: signal(null) } },
        { provide: PermissionService, useValue: { profile: signal(null) } },
      ],
    });
    databases = TestBed.inject(PosDatabaseService);
    databases.useTenant(`tenant-${crypto.randomUUID()}`);
    outbox = TestBed.inject(SaleOutboxService);
    http = TestBed.inject(HttpTestingController);
    await databases.db().products.put({
      productId: 'p-1',
      cip13: '3400930000001',
      ean13: null,
      designation: 'DOLIPRANE',
      designationKey: 'DOLIPRANE',
      dci: null,
      listing: 'NONE',
      vatRate: 2.1,
      unitPriceCents: 250,
      availableQuantity: 5,
      active: true,
      changedAt: '2026-10-18T00:00:00Z',
    });
  });

  afterEach(() => http.verify());

  it('commits the journal entry and the stock decrement together', async () => {
    await outbox.enqueue(sale('s-1'));

    const db = databases.db();
    expect((await db.outbox.get('s-1'))?.status).toBe('PENDING');
    expect((await db.products.get('p-1'))?.availableQuantity).toBe(3);
  });

  it('marks accepted and duplicate sales as synced and keeps rejected ones visible', async () => {
    await outbox.enqueue(sale('s-1'));
    await outbox.enqueue(sale('s-2', 'p-1', 1));
    await outbox.enqueue(sale('s-3', 'p-1', 1));

    const flushing = outbox.flush();
    const request = await vi.waitFor(() => http.expectOne(`${API}/v1/pos/sync/sales`));
    expect(request.request.body.sales.map((s: SaleRequest) => s.clientSaleId)).toEqual([
      's-1',
      's-2',
      's-3',
    ]);
    request.flush({
      serverTime: '2026-10-18T09:00:01Z',
      outcomes: [
        { type: 'ACCEPTED', clientSaleId: 's-1', saleId: 'srv-1', anomalies: [] },
        { type: 'DUPLICATE', clientSaleId: 's-2', saleId: 'srv-2' },
        {
          type: 'REJECTED',
          clientSaleId: 's-3',
          code: 'unknown-product',
          violations: [],
          message: 'Unknown product',
        },
      ],
    } satisfies SyncResponse);
    const report = await flushing;

    const db = databases.db();
    expect(report).toEqual({ sent: 3, synced: 2, rejected: 1, deferred: 0 });
    expect((await db.outbox.get('s-1'))?.status).toBe('SYNCED');
    expect((await db.outbox.get('s-2'))?.status).toBe('SYNCED');
    expect((await outbox.rejected()).map((entry) => entry.clientSaleId)).toEqual(['s-3']);
  });

  it('keeps sales pending and backs off when the server is unreachable', async () => {
    await outbox.enqueue(sale('s-1'));
    const now = Date.now();

    const flushing = outbox.flush(() => now);
    const request = await vi.waitFor(() => http.expectOne(`${API}/v1/pos/sync/sales`));
    request.error(new ProgressEvent('error'), { status: 0 });
    const report = await flushing;

    const entry = await databases.db().outbox.get('s-1');
    expect(report.deferred).toBe(1);
    expect(entry?.status).toBe('PENDING');
    expect(entry?.attempts).toBe(1);
    expect(entry?.nextAttemptAt).toBeGreaterThan(now);

    // Not due yet: nothing is sent
    expect(await outbox.flush(() => now)).toEqual({ sent: 0, synced: 0, rejected: 0, deferred: 0 });
  });

  it('grows the backoff exponentially up to five minutes', () => {
    const noJitter = () => 0.5;
    expect(backoffDelay(1, noJitter)).toBe(2_000);
    expect(backoffDelay(3, noJitter)).toBe(8_000);
    expect(backoffDelay(30, noJitter)).toBe(300_000);
  });
});
