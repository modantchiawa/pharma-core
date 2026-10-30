import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { APP_ENVIRONMENT } from '../config';
import { CatalogSyncService } from './catalog-sync.service';
import { SafetyService } from '../safety/safety.service';
import { PaymentNotificationsService } from '../payments/payment-notifications.service';
import { ConnectivityService } from './connectivity.service';
import { SaleOutboxService } from './sale-outbox.service';
import { TerminalService } from './terminal.service';
import { versionLabel } from '../update/build-info';

const FLUSH_INTERVAL_MS = 15_000;
const CATALOG_INTERVAL_MS = 5 * 60_000;
const HEARTBEAT_INTERVAL_MS = 60_000;

/** Wires every sync trigger: startup, connectivity regained, timers, tab focus and service worker. */
@Injectable({ providedIn: 'root' })
export class SyncOrchestratorService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly outbox = inject(SaleOutboxService);
  private readonly catalog = inject(CatalogSyncService);
  private readonly safety = inject(SafetyService);
  private readonly paymentNotifications = inject(PaymentNotificationsService);
  private readonly connectivity = inject(ConnectivityService);
  private readonly terminal = inject(TerminalService);
  private started = false;

  constructor() {
    effect(() => {
      if (this.started && this.connectivity.networkAvailable()) {
        void this.syncNow();
      }
    });
  }

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.outbox.watchCounters();
    void this.syncNow();
    setInterval(() => {
      void this.outbox.flush();
      void this.paymentNotifications.flush().catch(() => undefined);
    }, FLUSH_INTERVAL_MS);
    setInterval(() => {
      void this.catalog.pull().catch(() => undefined);
      void this.safety.pullRules().catch(() => undefined);
    }, CATALOG_INTERVAL_MS);
    setInterval(() => void this.heartbeat(), HEARTBEAT_INTERVAL_MS);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        void this.outbox.flush();
      }
    });
    navigator.serviceWorker?.addEventListener('message', (event: MessageEvent) => {
      if (event.data?.type === 'flush-outbox') {
        void this.outbox.flush();
      }
    });
  }

  async syncNow(): Promise<void> {
    await this.outbox.flush();
    await this.paymentNotifications.flush().catch(() => undefined);
    await this.catalog.pull().catch(() => undefined);
    await this.safety.pullRules().catch(() => undefined);
    await this.outbox.purgeSynced().catch(() => undefined);
    await this.heartbeat();
  }

  private async heartbeat(): Promise<void> {
    try {
      await firstValueFrom(
        this.http.post(`${this.env.apiUrl}/v1/pos/heartbeat`, {
          terminalId: this.terminal.terminalId,
          appVersion: versionLabel(),
          pendingSales: this.outbox.pendingCount(),
          lastSyncAt: this.outbox.lastSyncAt()?.toISOString() ?? null,
          rejectedSales: this.outbox.rejectedCount(),
        }),
      );
    } catch {
      // Heartbeats are best effort.
    }
  }
}
