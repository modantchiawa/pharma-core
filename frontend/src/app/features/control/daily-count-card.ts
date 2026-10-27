import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { DailyCount } from '../../core/api/models';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { errorDetail } from '../procurement/purchase-orders';
import { Icon } from '../../shared/ui/icon';

/**
 * "Contrôle du jour": the few products drawn for today's blind recount, why each was drawn,
 * and the button that opens the count. Shown on the dashboard and the stock screen.
 */
@Component({
  selector: 'app-daily-count-card',
  imports: [RouterLink, MoneyPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <section class="card daily" data-testid="daily-count-card" aria-labelledby="daily-title">
        <div class="card-header flex-wrap gap-3">
          <div class="flex min-w-0 items-center gap-3">
            <span class="kpi-icon"><app-icon name="counts" [size]="16" /></span>
            <div class="min-w-0">
              <h2 id="daily-title" class="card-title">Contrôle du jour</h2>
              <p class="text-xs text-muted">
                {{ subtitle() }}
              </p>
            </div>
          </div>
          <div class="flex flex-wrap items-center gap-2">
            @if (daily(); as d) {
              <span class="badge" [class]="badge()"><span class="dot"></span>{{ status() }}</span>
              @if (d.status === 'PENDING' || d.status === 'OPEN') {
                @if (canCount()) {
                  <button
                    type="button"
                    class="btn btn-primary btn-sm"
                    data-testid="daily-count-start"
                    [disabled]="busy()"
                    (click)="start()"
                  >
                    <app-icon [name]="d.status === 'OPEN' ? 'arrowRight' : 'counts'" [size]="15" />
                    {{ d.status === 'OPEN' ? 'Continuer le comptage' : 'Démarrer le contrôle' }}
                  </button>
                }
              } @else if (compact()) {
                <a
                  class="btn btn-ghost btn-sm"
                  routerLink="/control"
                  [queryParams]="{ tab: 'daily' }"
                  >Historique</a
                >
              }
            }
          </div>
        </div>
        @if (daily(); as d) {
          @if (d.items.length === 0) {
            <div class="empty-state">
              <app-icon name="stock" [size]="22" />
              <p>Aucun produit en stock à contrôler aujourd'hui.</p>
            </div>
          } @else {
            <ol class="items">
              @for (item of shown(); track item.productId) {
                <li class="item">
                  <span class="rank tabular-nums">{{ item.rank }}</span>
                  <div class="min-w-0 flex-1">
                    <p class="truncate text-sm font-medium">
                      {{ item.designation }}
                      @if (item.narcotic) {
                        <span class="badge badge-danger ml-1 align-middle">stupéfiant</span>
                      }
                    </p>
                    <p class="truncate text-xs text-subtle">
                      @if (item.locationCode) {
                        <app-icon
                          [name]="item.locationKind === 'SAFE' ? 'safe' : 'mapPin'"
                          [size]="12"
                          class="mr-0.5 align-[-2px]"
                        />{{ item.locationCode }} ·
                      }
                      {{ item.reasons.join(' · ') || 'tirage aléatoire' }}
                    </p>
                  </div>
                  <span class="score" [attr.title]="'Score de risque ' + item.score + ' / 100'">
                    <span class="score-bar" [style.width.%]="item.score"></span>
                  </span>
                </li>
              }
            </ol>
            @if (d.status === 'OPEN') {
              <div class="px-5 pb-4">
                <div class="progress" role="progressbar" [attr.aria-valuenow]="progress()">
                  <span [style.width.%]="progress()"></span>
                </div>
                <p class="mt-1 text-xs text-muted tabular-nums">
                  {{ d.countedLines }} / {{ d.lineCount }} lots comptés
                </p>
              </div>
            }
            @if (d.status === 'VALIDATED') {
              <div class="border-t border-line px-5 py-3 text-sm">
                @if (d.variances.length === 0) {
                  <p class="flex items-center gap-2 text-success">
                    <app-icon name="checkCircle" [size]="16" />
                    Validé{{ d.validatedBy ? ' par ' + d.validatedBy : '' }} : aucun écart.
                  </p>
                } @else {
                  @for (v of d.variances; track v.productId) {
                    <p class="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                      <app-icon name="alert" [size]="15" class="text-danger" />
                      <span class="font-medium">{{ v.designation }}</span>
                      <span class="badge badge-danger tabular-nums"
                        >{{ v.variance > 0 ? '+' : '' }}{{ v.variance }}</span
                      >
                      <span class="text-muted">{{ v.valueCents | money }}</span>
                    </p>
                  }
                }
              </div>
            }
          }
        } @else {
          <div class="space-y-2 p-5">
            <div class="skeleton h-4 w-2/3"></div>
            <div class="skeleton h-4 w-1/2"></div>
          </div>
        }
      </section>
    }
  `,
  styles: `
    .items {
      display: grid;
      gap: 0.25rem 1rem;
      padding: 0.25rem 0.75rem 0.75rem;
    }
    @media (min-width: 1024px) {
      .items {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
    .item {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.5rem;
      border-radius: var(--radius-sm);
    }
    .item:hover {
      background: var(--surface-2);
    }
    .rank {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.6rem;
      height: 1.6rem;
      flex-shrink: 0;
      border-radius: 999px;
      background: var(--surface-3);
      color: var(--text-muted);
      font-size: 0.75rem;
      font-weight: 600;
    }
    .score {
      width: 3.5rem;
      height: 0.4rem;
      flex-shrink: 0;
      overflow: hidden;
      border-radius: 999px;
      background: var(--surface-3);
    }
    .score-bar {
      display: block;
      height: 100%;
      border-radius: 999px;
      background: var(--brand-primary);
    }
    .progress {
      height: 0.4rem;
      overflow: hidden;
      border-radius: 999px;
      background: var(--surface-3);
    }
    .progress span {
      display: block;
      height: 100%;
      background: var(--brand-primary);
      transition: width 200ms ease;
    }
  `,
})
export class DailyCountCard {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly permissions = inject(PermissionService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/control/daily-count`;

  /** On the dashboard, the list is shortened and links to the history. */
  readonly compact = input(false);

  protected readonly daily = signal<DailyCount | null>(null);
  protected readonly busy = signal(false);
  protected readonly visible = computed(() =>
    this.permissions.hasAny('STOCK_COUNT', 'STOCK_COUNT_VALIDATE', 'CONTROL_VIEW'),
  );
  protected readonly canCount = computed(() =>
    this.permissions.hasAny('STOCK_COUNT', 'STOCK_COUNT_VALIDATE'),
  );
  protected readonly shown = computed(() => this.daily()?.items ?? []);
  protected readonly progress = computed(() => {
    const d = this.daily();
    return d && d.lineCount > 0 ? Math.round((d.countedLines / d.lineCount) * 100) : 0;
  });
  protected readonly status = computed(() => {
    switch (this.daily()?.status) {
      case 'OPEN':
        return 'En cours';
      case 'VALIDATED':
        return 'Validé';
      case 'CANCELLED':
        return 'Annulé';
      default:
        return 'À faire';
    }
  });
  protected readonly badge = computed(() => {
    const d = this.daily();
    if (!d) {
      return '';
    }
    if (d.status === 'VALIDATED') {
      return d.variances.length > 0 ? 'badge-danger' : 'badge-success';
    }
    return d.status === 'OPEN' ? 'badge-info' : 'badge-warning';
  });
  protected readonly subtitle = computed(() => {
    const d = this.daily();
    if (!d) {
      return 'Recomptage à l’aveugle de quelques produits tirés au sort';
    }
    return `${d.items.length} produit(s) à recompter à l’aveugle, tirés selon leur risque`;
  });

  constructor() {
    if (this.visible()) {
      void this.load();
    }
  }

  private async load(): Promise<void> {
    try {
      this.daily.set(await firstValueFrom(this.http.post<DailyCount>(this.api, null)));
    } catch {
      this.daily.set(null);
    }
  }

  protected async start(): Promise<void> {
    this.busy.set(true);
    try {
      const started = await firstValueFrom(this.http.post<DailyCount>(`${this.api}/start`, null));
      this.daily.set(started);
      await this.router.navigate(['/inventory/counts'], {
        queryParams: { count: started.stockCountId },
      });
    } catch (error) {
      this.toast.error(errorDetail(error, 'Le contrôle n’a pas pu démarrer'));
    } finally {
      this.busy.set(false);
    }
  }
}
