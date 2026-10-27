import { HttpClient } from '@angular/common/http';
import { Injectable, Injector, inject, signal } from '@angular/core';
import { Subject, Subscription, firstValueFrom } from 'rxjs';
import { ControlAlert } from '../api/models';
import { PermissionService } from '../auth/permission.service';
import { APP_ENVIRONMENT } from '../config';

/** Polling period when the live connection is down (hub restarting, Wi-Fi lost). */
const POLL_MS = 60_000;

/**
 * The owner's alert bell: unread count, pushed in real time over this node's STOMP broker
 * (topic of the pharmacy, reserved to CONTROL_VIEW), with a slow poll as a safety net. The
 * STOMP client is loaded on demand, so the counter screens do not carry it.
 */
@Injectable({ providedIn: 'root' })
export class ControlAlertsService {
  private readonly http = inject(HttpClient);
  private readonly injector = inject(Injector);
  private readonly permissions = inject(PermissionService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/control/alerts`;
  private readonly unreadState = signal(0);
  private readonly incoming = new Subject<ControlAlert>();
  private live: Subscription | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private started = false;
  /** Whether pushes arrive; false until the STOMP client is loaded and connected. */
  private liveConnected: () => boolean = () => false;

  readonly unread = this.unreadState.asReadonly();
  /** Alerts as they arrive, for the pages that show the feed. */
  readonly arrivals = this.incoming.asObservable();

  /**
   * Starts listening for the signed-in pharmacy; a no-op without CONTROL_VIEW. Called again when
   * the bell comes back after an unlock, it re-reads the count of the alerts held meanwhile.
   */
  start(): void {
    const tenant = this.permissions.profile()?.tenantId;
    if (!tenant || !this.permissions.has('CONTROL_VIEW')) {
      return;
    }
    if (this.started) {
      void this.refresh();
      return;
    }
    this.started = true;
    void this.refresh();
    void this.connect(tenant);
    this.timer = setInterval(() => {
      if (!this.liveConnected()) {
        void this.refresh();
      }
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
      const { unread } = await firstValueFrom(
        this.http.get<{ unread: number }>(`${this.api}/unread-count`),
      );
      this.unreadState.set(unread);
    } catch {
      // offline: the count is refreshed at the next poll or push
    }
  }

  /** Called by the pages after acknowledging alerts. */
  setUnread(count: number): void {
    this.unreadState.set(Math.max(0, count));
  }

  private async connect(tenant: string): Promise<void> {
    const { StompService } = await import('../realtime/stomp.service');
    if (!this.started) {
      return;
    }
    const stomp = this.injector.get(StompService);
    this.live = stomp
      .watch<ControlAlert>(`/topic/tenants.${tenant}.control-alerts`, 'local')
      .subscribe((alert) => {
        this.unreadState.update((n) => n + 1);
        this.incoming.next(alert);
      });
    this.liveConnected = () => stomp.localConnected();
  }
}
