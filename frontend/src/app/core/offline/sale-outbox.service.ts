import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { liveQuery } from 'dexie';
import { firstValueFrom } from 'rxjs';
import { SaleOutcome, SaleRequest, SyncResponse } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { ConnectivityService } from './connectivity.service';
import { OutboxSale } from './pos-database';
import { PosDatabaseService } from './pos-database.service';

const BATCH_SIZE = 50;
const MAX_BACKOFF_MS = 5 * 60_000;
const SYNCED_RETENTION_MS = 7 * 24 * 3_600_000;
export const FLUSH_LOCK = 'pharmacore-outbox-flush';
export const BACKGROUND_SYNC_TAG = 'pharmacore-outbox';

export interface FlushReport {
  sent: number;
  synced: number;
  rejected: number;
  deferred: number;
}

/**
 * Local-first sale journal (transactional outbox on the terminal).
 *
 * 1. A sale is final as soon as it is committed in IndexedDB together with the optimistic
 *    stock decrement: the ticket is printed whether or not the network is up.
 * 2. A flush loop replays pending sales to {@code /pos/sync/sales}; the server is idempotent on
 *    {@code clientSaleId}, so a lost response only produces a DUPLICATE acknowledgement.
 * 3. Only one flusher runs per device (Web Locks API), even with several tabs and the service
 *    worker; transient failures back off exponentially with jitter.
 */
@Injectable({ providedIn: 'root' })
export class SaleOutboxService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly databases = inject(PosDatabaseService);
  private readonly connectivity = inject(ConnectivityService);

  readonly pendingCount = signal(0);
  readonly rejectedCount = signal(0);
  readonly lastSyncAt = signal<Date | null>(null);

  private countersSubscription: { unsubscribe(): void } | null = null;

  /** Live pending/rejected counters (Dexie liveQuery), started once a pharmacy context exists. */
  watchCounters(): void {
    if (this.countersSubscription) {
      return;
    }
    const db = this.databases.db();
    this.countersSubscription = liveQuery(async () => ({
      pending: await db.outbox.where('status').equals('PENDING').count(),
      rejected: await db.outbox.where('status').equals('REJECTED').count(),
    })).subscribe(({ pending, rejected }) => {
      this.pendingCount.set(pending);
      this.rejectedCount.set(rejected);
    });
  }

  /** Commits the sale locally (journal entry + stock decrement) in one IndexedDB transaction. */
  async enqueue(sale: SaleRequest): Promise<OutboxSale> {
    const db = this.databases.db();
    const entry: OutboxSale = {
      clientSaleId: sale.clientSaleId,
      ticketNumber: sale.ticketNumber,
      payload: sale,
      status: 'PENDING',
      attempts: 0,
      nextAttemptAt: Date.now(),
      createdAt: Date.now(),
    };
    await db.transaction('rw', db.outbox, db.products, async () => {
      await db.outbox.add(entry);
      for (const line of sale.lines) {
        await db.products
          .where('productId')
          .equals(line.productId)
          .modify((product) => {
            product.availableQuantity = Math.max(product.availableQuantity - line.quantity, 0);
          });
      }
    });
    void this.requestBackgroundSync();
    return entry;
  }

  /** Replays due sales. Safe to call from anywhere, at any time. */
  async flush(now: () => number = Date.now): Promise<FlushReport> {
    const run = () => this.flushUnlocked(now);
    if (typeof navigator !== 'undefined' && navigator.locks) {
      return navigator.locks.request(FLUSH_LOCK, { ifAvailable: true }, async (lock) =>
        lock ? run() : { sent: 0, synced: 0, rejected: 0, deferred: 0 },
      );
    }
    return run();
  }

  async rejected(): Promise<OutboxSale[]> {
    return this.databases.db().outbox.where('status').equals('REJECTED').sortBy('createdAt');
  }

  /** Pharmacist acknowledged a rejected sale (handled manually in the back office). */
  async dismissRejected(clientSaleId: string): Promise<void> {
    await this.databases.db().outbox.delete(clientSaleId);
  }

  async purgeSynced(now = Date.now()): Promise<number> {
    const db = this.databases.db();
    return db.outbox
      .where('status')
      .equals('SYNCED')
      .filter((entry) => (entry.syncedAt ?? 0) < now - SYNCED_RETENTION_MS)
      .delete();
  }

  private async flushUnlocked(now: () => number): Promise<FlushReport> {
    const db = this.databases.db();
    const report: FlushReport = { sent: 0, synced: 0, rejected: 0, deferred: 0 };
    for (;;) {
      const due = await db.outbox
        .where('[status+nextAttemptAt]')
        .between(['PENDING', 0], ['PENDING', now()], true, true)
        .limit(BATCH_SIZE)
        .sortBy('createdAt');
      if (due.length === 0) {
        return report;
      }
      let response: SyncResponse;
      try {
        response = await firstValueFrom(
          this.http.post<SyncResponse>(`${this.env.apiUrl}/v1/pos/sync/sales`, {
            sales: due.map((entry) => entry.payload),
          }),
        );
        this.connectivity.reportServerReachable(true);
      } catch (error) {
        const transportFailure =
          !(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500;
        this.connectivity.reportServerReachable(!transportFailure);
        await this.defer(due, now(), describe(error));
        report.deferred += due.length;
        return report;
      }
      report.sent += due.length;
      await this.apply(due, response.outcomes, now(), report);
      this.lastSyncAt.set(new Date(now()));
      if (due.length < BATCH_SIZE) {
        return report;
      }
    }
  }

  private async apply(
    due: OutboxSale[],
    outcomes: SaleOutcome[],
    at: number,
    report: FlushReport,
  ): Promise<void> {
    const db = this.databases.db();
    const byId = new Map(outcomes.map((outcome) => [outcome.clientSaleId, outcome]));
    await db.transaction('rw', db.outbox, async () => {
      for (const entry of due) {
        const outcome = byId.get(entry.clientSaleId);
        switch (outcome?.type) {
          case 'ACCEPTED':
          case 'DUPLICATE':
            await db.outbox.update(entry.clientSaleId, { status: 'SYNCED', syncedAt: at, outcome });
            report.synced++;
            break;
          case 'REJECTED':
            await db.outbox.update(entry.clientSaleId, {
              status: 'REJECTED',
              outcome,
              lastError: outcome.message,
            });
            report.rejected++;
            break;
          case 'RETRY':
          case undefined:
            await db.outbox.update(
              entry.clientSaleId,
              retryChanges(entry, at, outcome?.message ?? 'No outcome'),
            );
            report.deferred++;
            break;
        }
      }
    });
  }

  private async defer(entries: OutboxSale[], at: number, reason: string): Promise<void> {
    const db = this.databases.db();
    await db.transaction('rw', db.outbox, async () => {
      for (const entry of entries) {
        await db.outbox.update(entry.clientSaleId, retryChanges(entry, at, reason));
      }
    });
  }

  private async requestBackgroundSync(): Promise<void> {
    try {
      const registration = await navigator.serviceWorker?.ready;
      const sync = (
        registration as ServiceWorkerRegistration & {
          sync?: { register(tag: string): Promise<void> };
        }
      )?.sync;
      await sync?.register(BACKGROUND_SYNC_TAG);
    } catch {
      // Background Sync is a Chromium extra; the in-app loop remains the primary mechanism.
    }
  }
}

/** Exponential backoff (2s, 4s, 8s... capped at 5 min) with ±20% jitter to avoid thundering herds. */
export function backoffDelay(attempts: number, random: () => number = Math.random): number {
  const base = Math.min(2_000 * 2 ** Math.max(attempts - 1, 0), MAX_BACKOFF_MS);
  return Math.round(base * (0.8 + random() * 0.4));
}

function retryChanges(entry: OutboxSale, at: number, reason: string): Partial<OutboxSale> {
  const attempts = entry.attempts + 1;
  return { attempts, nextAttemptAt: at + backoffDelay(attempts), lastError: reason };
}

function describe(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    return error.status === 0 ? 'Network unreachable' : `HTTP ${error.status}`;
  }
  return error instanceof Error ? error.message : String(error);
}
