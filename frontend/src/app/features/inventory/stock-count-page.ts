import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { StockCount, StockCountLine, StorageLocation } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';
import { parseGs1 } from '../../shared/gs1';
import { Icon } from '../../shared/ui/icon';

const STATUS_LABEL: Record<StockCount['status'], string> = {
  OPEN: 'En cours',
  VALIDATED: 'Validé',
  CANCELLED: 'Annulé',
};

const STATUS_BADGE: Record<StockCount['status'], string> = {
  OPEN: 'badge-info',
  VALIDATED: 'badge-success',
  CANCELLED: '',
};

/**
 * Blind physical inventory: the counter scans or picks a lot and types what is on the shelf;
 * expected quantities and variances appear only to the pharmacist who validates.
 */
@Component({
  selector: 'app-stock-count-page',
  imports: [FormsModule, DatePipe, MoneyPipe, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Inventaire</h1>
          <p class="page-subtitle">
            Comptage physique à l'aveugle : les écarts n'apparaissent qu'au pharmacien qui valide
          </p>
        </div>
      </header>

      <div class="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_340px]">
        <section class="min-w-0 space-y-4">
          @if (status(); as text) {
            <div class="alert alert-info" role="status">
              <app-icon name="info" [size]="16" class="mt-0.5" />
              <span>{{ text }}</span>
            </div>
          }
          @if (count(); as c) {
            <div class="card">
              <div class="card-header flex-wrap items-start">
                <div class="min-w-0">
                  <div class="flex flex-wrap items-center gap-2">
                    <h2 class="text-lg font-semibold">{{ c.label }}</h2>
                    <span class="badge" [class]="statusBadge(c.status)">
                      <span class="dot"></span>{{ statusLabel(c.status) }}
                    </span>
                  </div>
                  <p class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted">
                    <span class="inline-flex items-center gap-1.5">
                      <app-icon name="clock" [size]="14" />
                      démarré le {{ c.startedAt | date: 'dd/MM HH:mm' }}
                    </span>
                    @if (c.location) {
                      <span class="inline-flex items-center gap-1.5">
                        <app-icon name="mapPin" [size]="14" />
                        emplacement {{ c.location }}
                      </span>
                    }
                  </p>
                </div>
                @if (c.status === 'OPEN') {
                  <div class="flex gap-2" *appHasPermission="'STOCK_COUNT_VALIDATE'">
                    <button type="button" class="btn btn-secondary" (click)="cancel(c)">
                      Annuler
                    </button>
                    <button type="button" class="btn btn-primary" (click)="validate(c)">
                      <app-icon name="check" [size]="16" />
                      Valider et régulariser
                    </button>
                  </div>
                }
              </div>

              <div class="card-body space-y-4">
                <div class="grid grid-cols-2 gap-3 sm:grid-cols-3">
                  <div
                    class="rounded-[var(--radius)] bg-surface-2 p-3"
                    [class.col-span-2]="c.varianceUnits === undefined"
                  >
                    <p class="text-xs font-medium text-muted">Lots comptés</p>
                    <p class="mt-1 text-lg font-semibold tabular-nums">
                      {{ c.countedLines }} / {{ c.lineCount }}
                    </p>
                    <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-3">
                      <div
                        class="h-full rounded-full bg-brand transition-[width]"
                        [style.width.%]="progress(c)"
                      ></div>
                    </div>
                  </div>
                  @if (c.varianceUnits !== undefined) {
                    <div class="rounded-[var(--radius)] bg-surface-2 p-3">
                      <p class="text-xs font-medium text-muted">Écart</p>
                      <p
                        class="mt-1 text-lg font-semibold tabular-nums"
                        [class.text-danger]="c.varianceUnits < 0"
                      >
                        {{ c.varianceUnits }} unité(s)
                      </p>
                    </div>
                    <div class="col-span-2 rounded-[var(--radius)] bg-surface-2 p-3 sm:col-span-1">
                      <p class="text-xs font-medium text-muted">Valeur au coût</p>
                      <p
                        class="mt-1 text-lg font-semibold tabular-nums"
                        [class.text-danger]="c.varianceUnits < 0"
                      >
                        {{ c.varianceValueCents ?? 0 | money }}
                      </p>
                    </div>
                  }
                </div>

                @if (c.status === 'OPEN') {
                  <form class="flex flex-col gap-2 sm:flex-row" (ngSubmit)="scan(c)">
                    <label class="input-with-icon min-w-0 flex-1">
                      <app-icon name="barcode" [size]="16" />
                      <input
                        class="input"
                        placeholder="Scanner le code (DataMatrix GS1 ou EAN) ou taper le lot"
                        aria-label="Code ou lot"
                        [(ngModel)]="scanInput"
                        name="scan"
                        autofocus
                      />
                    </label>
                    <div class="flex gap-2">
                      <input
                        type="number"
                        min="0"
                        class="input num w-24"
                        placeholder="Qté"
                        aria-label="Quantité comptée"
                        [(ngModel)]="scanQuantity"
                        name="scanQuantity"
                      />
                      <button type="submit" class="btn btn-primary flex-1 sm:flex-none">
                        <app-icon name="counts" [size]="16" />
                        Compter
                      </button>
                    </div>
                  </form>
                }
                <label class="input-with-icon block">
                  <app-icon name="search" [size]="16" />
                  <input
                    type="search"
                    class="input"
                    placeholder="Filtrer les lignes (produit, lot)"
                    aria-label="Filtrer les lignes"
                    [(ngModel)]="filter"
                    name="filter"
                    (ngModelChange)="filterSignal.set($event)"
                  />
                </label>
              </div>

              <div class="overflow-x-auto border-t border-line">
                <table class="data-table">
                  <thead class="whitespace-nowrap">
                    <tr>
                      <th>Produit</th>
                      <th>Lot</th>
                      <th>Péremption</th>
                      @if (reveal()) {
                        <th class="num text-right">Attendu</th>
                      }
                      <th class="num text-right">Compté</th>
                      @if (reveal()) {
                        <th class="num text-right">Écart</th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (l of visibleLines(); track l.id) {
                      <tr
                        [class.variance-row]="
                          reveal() && l.variance !== undefined && l.variance !== 0
                        "
                        [class.text-subtle]="
                          l.countedQuantity === null || l.countedQuantity === undefined
                        "
                      >
                        <td class="min-w-56">
                          <p class="font-medium">{{ l.designation }}</p>
                          <p class="text-xs text-subtle tabular-nums">{{ l.cip13 }}</p>
                        </td>
                        <td class="font-mono text-xs">{{ l.lotNumber }}</td>
                        <td class="whitespace-nowrap">{{ l.expiryDate | date: 'MM/yyyy' }}</td>
                        @if (reveal()) {
                          <td class="num">{{ l.expectedQuantity }}</td>
                        }
                        <td class="num">
                          @if (c.status === 'OPEN') {
                            <input
                              type="number"
                              min="0"
                              class="input num ml-auto min-h-8 w-20"
                              [attr.aria-label]="'Quantité comptée, lot ' + l.lotNumber"
                              [ngModel]="l.countedQuantity"
                              (ngModelChange)="draft[l.id] = $event"
                              (blur)="countLine(c, l)"
                              (keydown.enter)="countLine(c, l)"
                              [name]="'q-' + l.id"
                            />
                          } @else {
                            {{ l.countedQuantity ?? '—' }}
                          }
                        </td>
                        @if (reveal()) {
                          <td class="num">
                            @if (l.variance !== undefined && l.variance !== 0) {
                              <span
                                class="badge"
                                [class.badge-danger]="l.variance < 0"
                                [class.badge-success]="l.variance > 0"
                                >{{ l.variance > 0 ? '+' : '' }}{{ l.variance }}</span
                              >
                            } @else {
                              <span class="font-semibold">{{ l.variance ?? '' }}</span>
                            }
                          </td>
                        }
                      </tr>
                    } @empty {
                      <tr>
                        <td [attr.colspan]="reveal() ? 6 : 4">
                          <div class="empty-state">
                            <app-icon name="search" [size]="22" />
                            <p>Aucune ligne ne correspond au filtre.</p>
                          </div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>
          } @else {
            <div class="card empty-state py-16">
              <app-icon name="counts" [size]="22" />
              <p class="font-medium text-ink">Aucun inventaire ouvert</p>
              <p>Sélectionnez un inventaire ou démarrez-en un nouveau.</p>
            </div>
          }
        </section>

        <aside class="space-y-4">
          <form class="card" (ngSubmit)="start()">
            <div class="card-header">
              <h3 class="card-title">Nouvel inventaire</h3>
            </div>
            <div class="card-body space-y-3">
              <label class="block">
                <span class="label">Libellé</span>
                <input
                  class="input"
                  placeholder="Libellé (ex. Rayon A, octobre)"
                  [(ngModel)]="label"
                  name="label"
                  required
                  maxlength="150"
                />
              </label>
              <label class="block">
                <span class="label">Emplacement</span>
                <select class="select" [(ngModel)]="locationId" name="location">
                  <option value="">Tout le stock</option>
                  @for (l of locations(); track l.id) {
                    <option [value]="l.id">
                      {{ l.code }} · {{ l.name }} ({{ l.productCount }})
                    </option>
                  }
                </select>
              </label>
              <p class="hint">
                Les quantités attendues sont figées au démarrage et cachées au compteur.
              </p>
              <button type="submit" class="btn btn-primary w-full">
                <app-icon name="counts" [size]="16" />
                Démarrer
              </button>
            </div>
          </form>
          <div class="card">
            <div class="card-header">
              <h3 class="card-title">Inventaires récents</h3>
              <span class="badge">{{ recent().length }}</span>
            </div>
            <ul class="p-2">
              @for (c of recent(); track c.id) {
                <li>
                  <button
                    type="button"
                    class="flex w-full items-center gap-3 rounded-[var(--radius-sm)] px-3 py-2.5 text-left text-sm transition-colors hover:bg-surface-2"
                    [class.bg-brand-soft]="c.id === count()?.id"
                    (click)="open(c.id)"
                  >
                    <span class="min-w-0 flex-1">
                      <span class="block truncate font-medium">{{ c.label }}</span>
                      <span class="block text-xs text-subtle tabular-nums">
                        {{ c.countedLines }}/{{ c.lineCount }} lots ·
                        {{ c.startedAt | date: 'dd/MM' }}
                      </span>
                    </span>
                    <span class="badge" [class]="statusBadge(c.status)">
                      {{ statusLabel(c.status) }}
                    </span>
                  </button>
                </li>
              } @empty {
                <li class="empty-state py-6">
                  <app-icon name="counts" [size]="22" />
                  <p>Aucun inventaire</p>
                </li>
              }
            </ul>
          </div>
        </aside>
      </div>
    </div>
  `,
  styles: `
    .variance-row td {
      background: var(--warning-soft);
    }
  `,
})
export class StockCountPage {
  private readonly http = inject(HttpClient);
  private readonly permissions = inject(PermissionService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/inventory/counts`;
  private readonly locationsApi = `${inject(APP_ENVIRONMENT).apiUrl}/v1/inventory/locations`;

  protected readonly count = signal<StockCount | null>(null);
  protected readonly recent = signal<StockCount[]>([]);
  protected readonly status = signal<string | null>(null);
  protected readonly filterSignal = signal('');
  protected readonly reveal = computed(
    () => this.permissions.has('STOCK_COUNT_VALIDATE') || this.count()?.status !== 'OPEN',
  );
  protected readonly visibleLines = computed(() => {
    const term = this.filterSignal().trim().toLowerCase();
    const lines = this.count()?.lines ?? [];
    return term
      ? lines.filter(
          (l) =>
            l.designation.toLowerCase().includes(term) ||
            l.lotNumber.toLowerCase().includes(term) ||
            l.cip13.includes(term),
        )
      : lines;
  });
  protected readonly draft: Record<string, number | null> = {};
  protected readonly locations = signal<StorageLocation[]>([]);
  protected label = '';
  /** Preselected from the Emplacements screen ("Inventorier"). */
  protected locationId = inject(ActivatedRoute).snapshot.queryParamMap.get('location') ?? '';
  protected filter = '';
  protected scanInput = '';
  protected scanQuantity: number | null = null;

  constructor() {
    void this.loadRecent();
    void this.loadLocations();
  }

  protected statusLabel(status: StockCount['status']): string {
    return STATUS_LABEL[status];
  }

  protected statusBadge(status: StockCount['status']): string {
    return STATUS_BADGE[status];
  }

  protected progress(c: StockCount): number {
    return c.lineCount > 0 ? Math.round((c.countedLines / c.lineCount) * 100) : 0;
  }

  protected async start(): Promise<void> {
    await this.run(() =>
      firstValueFrom(
        this.http.post<StockCount>(this.api, {
          label: this.label,
          locationId: this.locationId || null,
        }),
      ),
    );
    this.label = '';
    this.locationId = '';
    await this.loadRecent();
  }

  private async loadLocations(): Promise<void> {
    const locations = await firstValueFrom(this.http.get<StorageLocation[]>(this.locationsApi));
    this.locations.set(locations);
    const preselected = locations.find((l) => l.id === this.locationId);
    if (preselected && !this.label) {
      this.label = `Inventaire ${preselected.code}`;
    }
  }

  protected async open(id: string): Promise<void> {
    await this.run(() => firstValueFrom(this.http.get<StockCount>(`${this.api}/${id}`)));
  }

  protected async countLine(c: StockCount, line: StockCountLine): Promise<void> {
    const quantity = this.draft[line.id];
    if (quantity === undefined || quantity === null || quantity === line.countedQuantity) {
      return;
    }
    await this.run(() =>
      firstValueFrom(
        this.http.post<StockCount>(`${this.api}/${c.id}/lines/${line.id}`, {
          quantity: Number(quantity),
        }),
      ),
    );
  }

  /** A GS1 code carries product and lot; a plain lot number is matched against the lines. */
  protected async scan(c: StockCount): Promise<void> {
    const raw = this.scanInput.trim();
    if (!raw || this.scanQuantity === null) {
      this.status.set('Indiquez la quantité comptée');
      return;
    }
    const gs1 = parseGs1(raw);
    let line: StockCountLine | undefined;
    if (gs1?.ean13 && gs1.lot) {
      line = c.lines.find(
        (l) =>
          (l.ean13 === gs1.ean13 || l.cip13 === gs1.ean13) &&
          l.lotNumber.toLowerCase() === gs1.lot!.toLowerCase(),
      );
    } else {
      const matches = c.lines.filter(
        (l) =>
          l.lotNumber.toLowerCase() === raw.toLowerCase() || l.ean13 === raw || l.cip13 === raw,
      );
      line = matches.length === 1 ? matches[0] : undefined;
      if (matches.length > 1) {
        this.status.set('Plusieurs lots correspondent : précisez via la liste');
        return;
      }
    }
    if (!line) {
      this.status.set(`Aucun lot de l'inventaire ne correspond à « ${raw} »`);
      return;
    }
    this.draft[line.id] = this.scanQuantity;
    await this.countLine(c, line);
    this.scanInput = '';
    this.scanQuantity = null;
  }

  protected async validate(c: StockCount): Promise<void> {
    const notes = prompt('Observations (optionnel)') ?? '';
    await this.run(() =>
      firstValueFrom(
        this.http.post<StockCount>(`${this.api}/${c.id}/validation`, { notes: notes || null }),
      ),
    );
    await this.loadRecent();
  }

  protected async cancel(c: StockCount): Promise<void> {
    const reason = prompt("Motif d'annulation");
    if (!reason) {
      return;
    }
    await this.run(() =>
      firstValueFrom(this.http.post<StockCount>(`${this.api}/${c.id}/cancellation`, { reason })),
    );
    await this.loadRecent();
  }

  private async run(action: () => Promise<StockCount>): Promise<void> {
    this.status.set(null);
    try {
      const result = await action();
      this.count.set(result);
      for (const line of result.lines) {
        this.draft[line.id] = line.countedQuantity ?? null;
      }
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Opération refusée',
      );
    }
  }

  private async loadRecent(): Promise<void> {
    try {
      this.recent.set(
        await firstValueFrom(this.http.get<StockCount[]>(this.api, { params: { limit: 20 } })),
      );
    } catch {
      this.recent.set([]);
    }
  }
}
