import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ControlAlert, ControlAlertFeed } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { ControlAlertsService } from '../../core/control/control-alerts.service';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { ALERT_ICONS, ALERT_LABELS, severityBadge } from './control-format';

type Filter = 'unread' | 'all';

/** The owner's alert feed: what happened, who, how much, and acknowledgement. */
@Component({
  selector: 'app-alerts-page',
  imports: [DatePipe, RouterLink, MoneyPipe, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Alertes</h1>
          <p class="page-subtitle">
            Ce que le contrôle interne a détecté, au moment où cela s'est produit
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <a class="btn btn-secondary" routerLink="/control">
            <app-icon name="activity" [size]="16" />
            Contrôle interne
          </a>
          <a
            class="btn btn-ghost"
            routerLink="/control/settings"
            *appHasPermission="'CONTROL_SETTINGS'"
          >
            <app-icon name="settings" [size]="16" />
            Réglages
          </a>
          @if (unread() > 0) {
            <button type="button" class="btn btn-primary" (click)="acknowledgeAll()">
              <app-icon name="check" [size]="16" />
              Tout marquer comme lu
            </button>
          }
        </div>
      </header>

      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div class="segmented" role="tablist" aria-label="Filtre">
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="filter() === 'unread'"
            (click)="show('unread')"
          >
            Non lues
            @if (unread() > 0) {
              <span class="badge badge-danger ml-1 tabular-nums">{{ unread() }}</span>
            }
          </button>
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="filter() === 'all'"
            (click)="show('all')"
          >
            Toutes
          </button>
        </div>
        <p class="text-xs text-subtle">
          Aucune donnée client ou patient n'apparaît dans les alertes ni dans le webhook.
        </p>
      </div>

      <section class="space-y-3" aria-live="polite">
        @for (alert of visible(); track alert.id) {
          <article
            class="card alert-card"
            [class.is-unread]="!alert.acknowledgedAt"
            [attr.data-kind]="alert.kind"
          >
            <div class="flex gap-3 p-4 sm:p-5">
              <span class="alert-icon" [class]="badge(alert)">
                <app-icon [name]="icons[alert.kind]" [size]="18" />
              </span>
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-2">
                  <h2 class="text-sm font-semibold sm:text-base">{{ alert.title }}</h2>
                  <span class="badge" [class]="badge(alert)">{{ labels[alert.kind] }}</span>
                  @if (!alert.acknowledgedAt) {
                    <span class="badge badge-brand"><span class="dot"></span>Nouvelle</span>
                  }
                </div>
                <p class="mt-1 text-sm text-muted">{{ alert.detail }}</p>
                <p class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-subtle">
                  <span class="inline-flex items-center gap-1">
                    <app-icon name="clock" [size]="13" />{{
                      alert.occurredAt | date: 'EEEE d MMM, HH:mm'
                    }}
                  </span>
                  @if (alert.staffName) {
                    <span class="inline-flex items-center gap-1">
                      <app-icon name="user" [size]="13" />{{ alert.staffName }}
                    </span>
                  }
                  @if (alert.amountCents !== null) {
                    <span class="inline-flex items-center gap-1 tabular-nums">
                      <app-icon name="banknote" [size]="13" />{{ alert.amountCents | money }}
                    </span>
                  }
                  @if (alert.acknowledgedAt) {
                    <span class="inline-flex items-center gap-1">
                      <app-icon name="check" [size]="13" />vue{{
                        alert.acknowledgedBy ? ' par ' + alert.acknowledgedBy : ''
                      }}
                    </span>
                  }
                </p>
              </div>
              @if (!alert.acknowledgedAt) {
                <button
                  type="button"
                  class="btn btn-secondary btn-sm self-start"
                  [attr.aria-label]="'Marquer comme lue : ' + alert.title"
                  (click)="acknowledge(alert)"
                >
                  <app-icon name="check" [size]="14" />
                  <span class="hidden sm:inline">Vu</span>
                </button>
              }
            </div>
          </article>
        } @empty {
          <div class="card empty-state py-16">
            <app-icon name="checkCircle" [size]="22" />
            <p class="font-medium text-ink">
              {{ filter() === 'unread' ? 'Aucune alerte à traiter' : 'Aucune alerte' }}
            </p>
            <p>Les alertes arrivent ici en temps réel.</p>
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .alert-card.is-unread {
      border-color: color-mix(in srgb, var(--brand-primary) 35%, var(--border));
    }
    .alert-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.5rem;
      height: 2.5rem;
      flex-shrink: 0;
      border-radius: 0.8rem;
    }
  `,
})
export class AlertsPage {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly live = inject(ControlAlertsService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/control/alerts`;

  protected readonly icons = ALERT_ICONS;
  protected readonly labels = ALERT_LABELS;
  protected readonly filter = signal<Filter>('unread');
  protected readonly alerts = signal<ControlAlert[]>([]);
  protected readonly unread = signal(0);
  protected readonly visible = computed(() =>
    this.filter() === 'unread' ? this.alerts().filter((a) => !a.acknowledgedAt) : this.alerts(),
  );

  constructor() {
    void this.load();
    this.live.arrivals.pipe(takeUntilDestroyed()).subscribe((alert) => {
      this.alerts.update((list) => [alert, ...list]);
      this.unread.update((n) => n + 1);
    });
  }

  protected badge(alert: ControlAlert): string {
    return severityBadge(alert);
  }

  protected show(filter: Filter): void {
    this.filter.set(filter);
  }

  private async load(): Promise<void> {
    try {
      const feed = await firstValueFrom(
        this.http.get<ControlAlertFeed>(this.api, { params: { limit: 200 } }),
      );
      this.alerts.set(feed.alerts);
      this.unread.set(feed.unread);
      this.live.setUnread(feed.unread);
    } catch {
      this.toast.error('Alertes indisponibles');
    }
  }

  protected async acknowledge(alert: ControlAlert): Promise<void> {
    try {
      const updated = await firstValueFrom(
        this.http.post<ControlAlert>(`${this.api}/${alert.id}/acknowledgement`, null),
      );
      this.alerts.update((list) => list.map((a) => (a.id === updated.id ? updated : a)));
      this.unread.update((n) => Math.max(0, n - 1));
      this.live.setUnread(this.unread());
    } catch {
      this.toast.error('Alerte non mise à jour');
    }
  }

  protected async acknowledgeAll(): Promise<void> {
    try {
      const { acknowledged } = await firstValueFrom(
        this.http.post<{ acknowledged: number }>(`${this.api}/acknowledgement`, null),
      );
      this.toast.success(`${acknowledged} alerte(s) marquée(s) comme lue(s)`);
      await this.load();
    } catch {
      this.toast.error('Alertes non mises à jour');
    }
  }
}
