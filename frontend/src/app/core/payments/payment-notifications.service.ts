import { HttpClient } from '@angular/common/http';
import { Injectable, Injector, computed, inject, signal } from '@angular/core';
import { Subject, Subscription, firstValueFrom } from 'rxjs';
import { ManualPaymentNotice, PaymentNotification, PaymentNotificationFeed } from '../api/models';
import { PermissionService } from '../auth/permission.service';
import { APP_ENVIRONMENT } from '../config';
import { OutboxPaymentNotice } from '../offline/pos-database';
import { PosDatabaseService } from '../offline/pos-database.service';
import { TerminalService } from '../offline/terminal.service';
import { uuidv7 } from '../util/uuid';

/** Polling period when the live connection is down (hub restarting, Wi-Fi lost). */
const POLL_MS = 30_000;
const COUNTER_PERMISSIONS = ['SALE_CREATE', 'PAYMENT_RECONCILE', 'CASH_SESSION_MANAGE'];

/**
 * Payment confirmations as the pharmacy receives them (operator SMS forwarded by its phone,
 * webhooks, messages typed by the team), pushed to every terminal over this node's broker: on a
 * hub, the local network carries them without Internet. A confirmation typed on a terminal that
 * cannot reach the server waits in IndexedDB and is replayed with the sales.
 */
@Injectable({ providedIn: 'root' })
export class PaymentNotificationsService {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private readonly permissions = inject(PermissionService);
  private readonly databases = inject(PosDatabaseService);
  private readonly terminal = inject(TerminalService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/payments/notifications`;
  private readonly openState = signal<PaymentNotification[]>([]);
  private readonly incoming = new Subject<PaymentNotification>();
  private live: Subscription | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private started = false;
  private liveConnected: () => boolean = () => false;

  /** Confirmations received and not yet attached to a payment, newest first. */
  readonly open = this.openState.asReadonly();
  readonly openCount = computed(() => this.openState().length);
  /** Every confirmation as it arrives (matched or not), for toasts and lists. */
  readonly arrivals = this.incoming.asObservable();
  /** Confirmations typed here that the server has not taken yet. */
  readonly queued = signal(0);

  start(): void {
    const tenant = this.permissions.profile()?.tenantId;
    if (!tenant || !this.permissions.hasAny(...COUNTER_PERMISSIONS)) {
      return;
    }
    if (this.started) {
      void this.refresh();
      return;
    }
    this.started = true;
    void this.refresh();
    void this.countQueued();
    void this.connect(tenant);
    this.timer = setInterval(() => {
      if (!this.liveConnected()) {
        void this.refresh();
      }
      void this.flush();
    }, POLL_MS);
  }

  stop(): void {
    this.started = false;
    this.live?.unsubscribe();
    this.live = null;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  async refresh(): Promise<void> {
    try {
      const feed = await firstValueFrom(
        this.http.get<PaymentNotificationFeed>(this.api, {
          params: { status: 'UNMATCHED', limit: 50 },
        }),
      );
      this.openState.set(feed.notifications);
    } catch {
      // server unreachable: the list is refreshed at the next poll or push
    }
  }

  /** The open confirmations that could justify a payment of this amount and operator. */
  candidates(amountCents: number, provider: string | undefined): PaymentNotification[] {
    return this.openState().filter(
      (n) => n.amountCents === amountCents && (!provider || !n.provider || n.provider === provider),
    );
  }

  /** Forgets a confirmation attached to a payment of this terminal (the server confirms on sync). */
  consume(notificationId: string): void {
    this.openState.update((list) => list.filter((n) => n.id !== notificationId));
  }

  /**
   * A confirmation typed by the team (the SMS read on the phone). Sent at once when the server
   * answers, otherwise kept locally and replayed; either way the caller gets the entry.
   */
  async record(
    notice: Omit<ManualPaymentNotice, 'clientId' | 'receivedAt' | 'terminalId'>,
  ): Promise<{ queued: boolean; notification: PaymentNotification | null }> {
    const payload: ManualPaymentNotice = {
      ...notice,
      clientId: uuidv7(Date.now()),
      receivedAt: new Date().toISOString(),
      terminalId: this.terminal.terminalId,
    };
    try {
      const notification = await firstValueFrom(
        this.http.post<PaymentNotification>(this.api, payload),
      );
      if (notification.status === 'UNMATCHED') {
        this.openState.update((list) => [
          notification,
          ...list.filter((n) => n.id !== notification.id),
        ]);
      }
      return { queued: false, notification };
    } catch (error) {
      const status = (error as { status?: number }).status ?? 0;
      if (status >= 400 && status < 500) {
        throw error;
      }
      const entry: OutboxPaymentNotice = {
        clientId: payload.clientId,
        payload,
        status: 'PENDING',
        attempts: 0,
        createdAt: Date.now(),
      };
      await this.databases.db().paymentNotices.add(entry);
      await this.countQueued();
      return { queued: true, notification: null };
    }
  }

  /** Replays the confirmations typed while the server was unreachable. */
  async flush(): Promise<number> {
    const db = this.databases.db();
    const pending = await db.paymentNotices.where('status').equals('PENDING').sortBy('createdAt');
    let sent = 0;
    for (const entry of pending) {
      try {
        await firstValueFrom(this.http.post<PaymentNotification>(this.api, entry.payload));
        await db.paymentNotices.delete(entry.clientId);
        sent++;
      } catch (error) {
        const status = (error as { status?: number }).status ?? 0;
        if (status >= 400 && status < 500) {
          // refused for good (validation): dropped, the team retypes it if needed
          await db.paymentNotices.delete(entry.clientId);
        } else {
          await db.paymentNotices.update(entry.clientId, {
            attempts: entry.attempts + 1,
            lastError: (error as Error).message,
          });
          break;
        }
      }
    }
    await this.countQueued();
    if (sent > 0) {
      await this.refresh();
    }
    return sent;
  }

  private async countQueued(): Promise<void> {
    try {
      this.queued.set(
        await this.databases.db().paymentNotices.where('status').equals('PENDING').count(),
      );
    } catch {
      this.queued.set(0);
    }
  }

  private async connect(tenant: string): Promise<void> {
    const { StompService } = await import('../realtime/stomp.service');
    if (!this.started) {
      return;
    }
    const stomp = this.injector.get(StompService);
    this.live = stomp
      .watch<PaymentNotification>(`/topic/tenants.${tenant}.payments`, 'local')
      .subscribe((notification) => {
        this.openState.update((list) => {
          const others = list.filter((n) => n.id !== notification.id);
          return notification.status === 'UNMATCHED' ? [notification, ...others] : others;
        });
        this.incoming.next(notification);
      });
    this.liveConnected = () => stomp.localConnected();
  }
}
