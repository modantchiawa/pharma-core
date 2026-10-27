import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ControlAlert, ControlAlertFeed } from '../../core/api/models';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { ControlAlertsService } from '../../core/control/control-alerts.service';
import { ToastService } from '../../core/ui/toast.service';
import { Icon } from '../../shared/ui/icon';
import { ALERT_ICONS, severityBadge, shortWhen } from './control-format';

/**
 * Bell of the top bar for the owner's team: unread internal control alerts, pushed live, with
 * the latest ones in a popover and a link to the full feed.
 */
@Component({
  selector: 'app-alerts-bell',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:click)': 'onDocumentClick($event)' },
  template: `
    @if (allowed()) {
      <div class="relative">
        <button
          type="button"
          class="btn btn-ghost btn-icon relative"
          data-testid="alerts-bell"
          [attr.aria-label]="
            alerts.unread() > 0 ? alerts.unread() + ' alerte(s) non lue(s)' : 'Alertes'
          "
          [attr.aria-expanded]="open()"
          (click)="toggle()"
        >
          <app-icon name="bell" />
          @if (alerts.unread() > 0) {
            <span class="count tabular-nums">{{
              alerts.unread() > 99 ? '99+' : alerts.unread()
            }}</span>
          }
        </button>
        @if (open()) {
          <div class="panel animate-in" role="dialog" aria-label="Alertes du contrôle interne">
            <div class="flex items-center justify-between gap-2 px-4 py-3">
              <p class="text-sm font-semibold">Alertes</p>
              @if (alerts.unread() > 0) {
                <button type="button" class="btn btn-ghost btn-sm" (click)="acknowledgeAll()">
                  Tout marquer comme lu
                </button>
              }
            </div>
            <ul class="max-h-[60vh] overflow-y-auto border-t border-line">
              @for (alert of latest(); track alert.id) {
                <li class="item" [class.unread]="!alert.acknowledgedAt">
                  <span class="icon" [class]="severityBadge(alert)">
                    <app-icon [name]="icons[alert.kind]" [size]="15" />
                  </span>
                  <div class="min-w-0 flex-1">
                    <p class="text-sm font-medium leading-snug">{{ alert.title }}</p>
                    <p class="line-clamp-2 text-xs text-muted">{{ alert.detail }}</p>
                    <p class="mt-0.5 text-[0.7rem] text-subtle">
                      {{ when(alert.occurredAt) }}
                    </p>
                  </div>
                </li>
              } @empty {
                <li class="empty-state py-8">
                  <app-icon name="checkCircle" [size]="22" />
                  <p>Aucune alerte</p>
                </li>
              }
            </ul>
            <a
              routerLink="/control/alerts"
              class="block border-t border-line px-4 py-2.5 text-center text-sm font-medium text-brand hover:bg-surface-2"
              (click)="open.set(false)"
              >Voir toutes les alertes</a
            >
          </div>
        }
      </div>
    }
  `,
  styles: `
    .count {
      position: absolute;
      top: 0.15rem;
      right: 0.1rem;
      min-width: 1.1rem;
      height: 1.1rem;
      padding: 0 0.3rem;
      border: 2px solid var(--surface);
      border-radius: 999px;
      background: var(--danger);
      color: #fff;
      font-size: 0.62rem;
      font-weight: 700;
      line-height: 0.8rem;
      text-align: center;
    }
    .panel {
      position: fixed;
      top: 4rem;
      right: 0.75rem;
      left: 0.75rem;
      z-index: 45;
      overflow: hidden;
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      box-shadow: var(--shadow-lg);
    }
    @media (min-width: 640px) {
      .panel {
        position: absolute;
        top: calc(100% + 0.5rem);
        right: 0;
        left: auto;
        width: 24rem;
      }
    }
    .item {
      display: flex;
      gap: 0.75rem;
      padding: 0.75rem 1rem;
    }
    .item + .item {
      border-top: 1px solid var(--border);
    }
    .item.unread {
      background: var(--brand-softer);
    }
    .icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.9rem;
      height: 1.9rem;
      flex-shrink: 0;
      padding: 0;
      border-radius: 999px;
    }
  `,
})
export class AlertsBell {
  protected readonly alerts = inject(ControlAlertsService);
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly permissions = inject(PermissionService);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/control/alerts`;

  protected readonly icons = ALERT_ICONS;
  protected readonly severityBadge = severityBadge;
  protected readonly when = shortWhen;
  protected readonly open = signal(false);
  protected readonly latest = signal<ControlAlert[]>([]);
  protected readonly allowed = computed(
    () => !!this.permissions.profile() && this.permissions.has('CONTROL_VIEW'),
  );

  constructor() {
    effect(() => {
      if (this.allowed()) {
        this.alerts.start();
      }
    });
    this.alerts.arrivals.pipe(takeUntilDestroyed()).subscribe((alert) => {
      this.latest.update((list) => [alert, ...list].slice(0, 8));
      this.toast.info(alert.title);
    });
    inject(DestroyRef).onDestroy(() => this.alerts.stop());
  }

  protected async toggle(): Promise<void> {
    this.open.update((open) => !open);
    if (this.open()) {
      try {
        const feed = await firstValueFrom(
          this.http.get<ControlAlertFeed>(this.api, { params: { limit: 8 } }),
        );
        this.latest.set(feed.alerts);
        this.alerts.setUnread(feed.unread);
      } catch {
        this.latest.set([]);
      }
    }
  }

  protected async acknowledgeAll(): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`${this.api}/acknowledgement`, null));
      this.alerts.setUnread(0);
      const now = new Date().toISOString();
      this.latest.update((list) =>
        list.map((a) => ({ ...a, acknowledgedAt: a.acknowledgedAt ?? now })),
      );
    } catch {
      this.toast.error('Les alertes n’ont pas pu être marquées comme lues');
    }
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }
}
