import { HttpClient, HttpParams } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { StockLocation, StorageLocation, StorageLocationKind } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import {
  LOCATION_CODE_MAX,
  LOCATION_KINDS,
  groupByKind,
  kindInfo,
  normalizeLocationCode,
} from '../../shared/storage-location';
import { Icon } from '../../shared/ui/icon';

interface LocationProduct {
  productId: string;
  cip13: string;
  designation: string;
  available: number;
  onHand: number;
  location: StockLocation | null;
}

interface LocationForm {
  id: string | null;
  code: string;
  name: string;
  kind: StorageLocationKind;
  sortOrder: number;
}

/**
 * Storage plan of the pharmacy: locations grouped by kind with their product count, creation,
 * edition, deactivation (moving the products elsewhere), the products of one location to tidy a
 * shelf, a count of that location and an A4 sheet of shelf labels.
 */
@Component({
  selector: 'app-locations-page',
  imports: [FormsModule, RouterLink, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in print:hidden">
      <header class="page-header">
        <div>
          <h1 class="page-title">Emplacements</h1>
          <p class="page-subtitle">
            Où chaque produit est rangé : rayons, tiroirs, réfrigérateur, coffre, réserve
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          <button
            type="button"
            class="btn btn-secondary"
            [disabled]="activeLocations().length === 0"
            (click)="printLabels(activeLocations())"
          >
            <app-icon name="printer" [size]="16" />
            Étiquettes
          </button>
          <button
            *appHasPermission="'STOCK_LOCATION_MANAGE'"
            type="button"
            class="btn btn-primary"
            (click)="startCreate()"
          >
            <app-icon name="plus" [size]="16" />
            Nouvel emplacement
          </button>
        </div>
      </header>

      <div class="space-y-5">
        <div class="grid grid-cols-2 gap-4 lg:grid-cols-3">
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="locations" [size]="16" /></span>
              Emplacements
            </div>
            <div class="kpi-value">{{ activeLocations().length }}</div>
            <div class="kpi-foot">{{ groups().length }} type(s) de rangement</div>
          </div>
          <div class="card kpi">
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon name="stock" [size]="16" /></span>
              Produits rangés
            </div>
            <div class="kpi-value">{{ placedCount() }}</div>
            <div class="kpi-foot">Visibles au comptoir, même hors ligne</div>
          </div>
          <div class="card kpi col-span-2 lg:col-span-1">
            <div class="kpi-label">
              <span class="kpi-icon kpi-icon-warning"><app-icon name="alert" [size]="16" /></span>
              Sans emplacement
            </div>
            <div class="kpi-value" [class.text-warning]="unplacedCount() > 0">
              {{ unplacedCount() }}
            </div>
            <div class="kpi-foot">
              @if (unplacedCount() > 0) {
                <a
                  class="font-medium text-brand underline-offset-2 hover:underline"
                  routerLink="/inventory"
                  [queryParams]="{ location: 'NONE' }"
                  >Les ranger depuis Stocks</a
                >
              } @else {
                Tout le stock est rangé
              }
            </div>
          </div>
        </div>

        @if (form(); as f) {
          <form class="card" (ngSubmit)="save()" data-testid="location-form">
            <div class="card-header">
              <h2 class="card-title">
                {{ f.id ? 'Modifier l’emplacement' : 'Nouvel emplacement' }}
              </h2>
              <button
                type="button"
                class="btn btn-ghost btn-icon btn-sm"
                aria-label="Fermer"
                (click)="form.set(null)"
              >
                <app-icon name="x" [size]="16" />
              </button>
            </div>
            <div
              class="card-body grid gap-4 sm:grid-cols-2 lg:grid-cols-[10rem_minmax(0,1fr)_14rem_8rem]"
            >
              <label class="block">
                <span class="label">Code</span>
                <input
                  class="input font-semibold uppercase"
                  name="code"
                  required
                  [attr.maxlength]="codeMax"
                  placeholder="A3"
                  [(ngModel)]="f.code"
                />
                <span class="hint" [class.text-danger]="f.code.trim() && !codePreview(f.code)">
                  @if (f.code.trim() && !codePreview(f.code)) {
                    Lettres, chiffres, espace, . _ / -
                  } @else {
                    Sur l’étiquette et au comptoir
                  }
                </span>
              </label>
              <label class="block">
                <span class="label">Nom</span>
                <input
                  class="input"
                  name="name"
                  required
                  maxlength="80"
                  placeholder="Rayon A · étagère 3"
                  [(ngModel)]="f.name"
                />
              </label>
              <label class="block">
                <span class="label">Type</span>
                <select class="select" name="kind" [(ngModel)]="f.kind">
                  @for (k of kinds; track k.kind) {
                    <option [value]="k.kind">{{ k.label }}</option>
                  }
                </select>
                <span class="hint">{{ kindInfo(f.kind).hint }}</span>
              </label>
              <label class="block">
                <span class="label">Ordre</span>
                <input
                  type="number"
                  min="0"
                  class="input num"
                  name="sortOrder"
                  [(ngModel)]="f.sortOrder"
                />
                <span class="hint">Tri dans la liste</span>
              </label>
            </div>
            <div class="flex justify-end gap-2 border-t border-line px-5 py-3">
              <button type="button" class="btn btn-ghost" (click)="form.set(null)">Annuler</button>
              <button
                type="submit"
                class="btn btn-primary"
                [disabled]="busy() || !codePreview(f.code) || !f.name.trim()"
              >
                <app-icon name="check" [size]="16" />
                Enregistrer
              </button>
            </div>
          </form>
        }

        <div class="layout grid grid-cols-1 items-start gap-5" [class.with-detail]="selected()">
          @if (selected(); as l) {
            <section
              #detail
              class="card min-w-0 xl:sticky xl:top-5 xl:order-last"
              data-testid="location-detail"
              [attr.aria-label]="'Produits de ' + l.code"
            >
              <div class="card-header items-start">
                <div class="flex min-w-0 items-center gap-3">
                  <span class="loc-icon" [attr.data-kind]="l.kind">
                    <app-icon [name]="kindInfo(l.kind).icon" [size]="18" />
                  </span>
                  <div class="min-w-0">
                    <h2 class="card-title truncate">{{ l.code }} · {{ l.name }}</h2>
                    <p class="text-xs text-muted">
                      {{ kindInfo(l.kind).label }} · {{ l.productCount }} produit(s)
                      @if (!l.active) {
                        · désactivé
                      }
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  class="btn btn-ghost btn-icon btn-sm"
                  aria-label="Fermer"
                  (click)="selectedId.set(null)"
                >
                  <app-icon name="x" [size]="16" />
                </button>
              </div>
              <div class="flex flex-wrap gap-2 border-b border-line px-5 py-3">
                <a
                  *appHasPermission="['STOCK_COUNT', 'STOCK_COUNT_VALIDATE']"
                  class="btn btn-secondary btn-sm"
                  routerLink="/inventory/counts"
                  [queryParams]="{ location: l.id }"
                >
                  <app-icon name="counts" [size]="14" />
                  Inventorier
                </a>
                <button type="button" class="btn btn-secondary btn-sm" (click)="printLabels([l])">
                  <app-icon name="printer" [size]="14" />
                  Étiquette
                </button>
                @if (canManage()) {
                  <button type="button" class="btn btn-secondary btn-sm" (click)="startEdit(l)">
                    <app-icon name="pencil" [size]="14" />
                    Modifier
                  </button>
                  @if (l.active) {
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm text-danger"
                      (click)="deactivating.set(true)"
                    >
                      <app-icon name="ban" [size]="14" />
                      Désactiver
                    </button>
                  } @else {
                    <button type="button" class="btn btn-soft btn-sm" (click)="reactivate(l)">
                      <app-icon name="refresh" [size]="14" />
                      Réactiver
                    </button>
                  }
                }
              </div>
              @if (deactivating() && l.active) {
                <div class="space-y-3 border-b border-line bg-surface-2 px-5 py-4">
                  @if (l.productCount > 0) {
                    <p class="text-sm">
                      {{ l.productCount }} produit(s) sont encore rangés ici. Choisissez où les
                      déplacer avant de désactiver {{ l.code }}.
                    </p>
                    <select
                      class="select"
                      aria-label="Déplacer les produits vers"
                      [(ngModel)]="reassignTo"
                    >
                      <option value="">Déplacer vers…</option>
                      @for (other of activeLocations(); track other.id) {
                        @if (other.id !== l.id) {
                          <option [value]="other.id">{{ other.code }} · {{ other.name }}</option>
                        }
                      }
                    </select>
                  } @else {
                    <p class="text-sm">
                      {{ l.code }} est vide : il disparaîtra des listes, son historique est
                      conservé.
                    </p>
                  }
                  <div class="flex justify-end gap-2">
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm"
                      (click)="cancelDeactivation()"
                    >
                      Annuler
                    </button>
                    <button
                      type="button"
                      class="btn btn-danger btn-sm"
                      [disabled]="busy() || (l.productCount > 0 && !reassignTo)"
                      (click)="deactivate(l)"
                    >
                      Désactiver {{ l.code }}
                    </button>
                  </div>
                </div>
              }
              <ul class="max-h-[60vh] divide-y divide-[var(--border)] overflow-auto">
                @for (p of products(); track p.productId) {
                  <li class="flex items-center gap-3 px-5 py-2.5">
                    <span class="detail-cell">{{ p.location?.detail ?? '—' }}</span>
                    <span class="min-w-0 flex-1">
                      <span class="block truncate text-sm font-medium">{{ p.designation }}</span>
                      <span class="block text-xs text-subtle tabular-nums">{{ p.cip13 }}</span>
                    </span>
                    <span
                      class="badge"
                      [class.badge-danger]="p.available <= 0"
                      [class.badge-success]="p.available > 0"
                    >
                      {{ p.available }}
                    </span>
                  </li>
                } @empty {
                  <li class="empty-state">
                    <app-icon name="stock" [size]="22" />
                    <p>Aucun produit rangé ici.</p>
                  </li>
                }
              </ul>
            </section>
          }

          <div class="min-w-0 space-y-5">
            <div class="flex items-center justify-between gap-3">
              <p class="text-sm text-muted">
                Ouvrez un emplacement pour voir ses produits, dans l’ordre des cases.
              </p>
              <label class="flex shrink-0 items-center gap-2 text-sm text-muted">
                <input
                  type="checkbox"
                  [ngModel]="showInactive()"
                  (ngModelChange)="showInactive.set($event)"
                />
                Désactivés
              </label>
            </div>
            @for (g of groups(); track g.kind) {
              <section [attr.aria-label]="g.label">
                <h2 class="mb-2.5 flex items-center gap-2 text-sm font-semibold">
                  <app-icon [name]="g.icon" [size]="16" class="text-muted" />
                  {{ g.label }}
                  <span class="font-normal text-subtle">· {{ g.hint }}</span>
                </h2>
                <div class="cards grid gap-3 sm:grid-cols-2" [class.wide]="!selected()">
                  @for (l of g.locations; track l.id) {
                    <button
                      type="button"
                      class="card loc-card"
                      [class.loc-selected]="l.id === selectedId()"
                      [class.loc-inactive]="!l.active"
                      [attr.aria-pressed]="l.id === selectedId()"
                      (click)="select(l)"
                    >
                      <span class="loc-icon" [attr.data-kind]="l.kind">
                        <app-icon [name]="g.icon" [size]="18" />
                      </span>
                      <span class="min-w-0 flex-1 text-left">
                        <span class="flex items-center justify-between gap-2">
                          <span class="loc-code">{{ l.code }}</span>
                          @if (l.active) {
                            <span class="badge" [class.badge-brand]="l.productCount > 0">
                              {{ l.productCount }} produit(s)
                            </span>
                          } @else {
                            <span class="badge">Désactivé</span>
                          }
                        </span>
                        <span class="block truncate text-sm text-muted" [attr.title]="l.name">
                          {{ l.name }}
                        </span>
                      </span>
                    </button>
                  }
                </div>
              </section>
            } @empty {
              <div class="card">
                <div class="empty-state">
                  <app-icon name="locations" [size]="22" />
                  <p>Aucun emplacement. Créez vos rayons, tiroirs, frigo et coffre.</p>
                </div>
              </div>
            }
          </div>
        </div>
      </div>
    </div>

    <section class="labels hidden print:grid" aria-hidden="true">
      @for (l of labels(); track l.id) {
        <article class="shelf-label">
          <div class="shelf-code">{{ l.code }}</div>
          <div class="shelf-name">{{ l.name }}</div>
          <div class="shelf-kind">{{ kindInfo(l.kind).label }}</div>
        </article>
      }
    </section>
  `,
  styles: `
    .kpi-icon-warning {
      background: var(--warning-soft);
      color: var(--warning);
    }
    @media (min-width: 1280px) {
      .layout.with-detail {
        grid-template-columns: minmax(0, 1fr) 26rem;
      }
      .cards.wide {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
    }
    @media (min-width: 1536px) {
      .cards {
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }
      .cards.wide {
        grid-template-columns: repeat(4, minmax(0, 1fr));
      }
    }
    .loc-card {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      width: 100%;
      padding: 0.85rem 1rem;
      text-align: left;
      transition:
        border-color 120ms ease,
        box-shadow 120ms ease;
    }
    .loc-card:hover {
      border-color: color-mix(in oklab, var(--brand-primary) 45%, var(--border));
    }
    .loc-selected {
      border-color: var(--brand-primary);
      box-shadow: 0 0 0 3px var(--brand-ring);
    }
    .loc-inactive {
      opacity: 0.6;
    }
    .loc-icon {
      display: inline-flex;
      flex-shrink: 0;
      align-items: center;
      justify-content: center;
      width: 2.5rem;
      height: 2.5rem;
      border-radius: var(--radius-sm);
      background: var(--surface-3);
      color: var(--text-muted);
    }
    .loc-icon[data-kind='SHELF'],
    .loc-icon[data-kind='DRAWER'] {
      background: var(--brand-soft);
      color: var(--brand-text);
    }
    .loc-icon[data-kind='FRIDGE'] {
      background: var(--info-soft);
      color: var(--info);
    }
    .loc-icon[data-kind='SAFE'] {
      background: var(--warning-soft);
      color: var(--warning);
    }
    .loc-code {
      display: block;
      font-size: 1.05rem;
      font-weight: 700;
      letter-spacing: 0.02em;
      font-variant-numeric: tabular-nums;
    }
    .detail-cell {
      width: 4.5rem;
      flex-shrink: 0;
      color: var(--text-muted);
      font-size: 0.75rem;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
    @media print {
      .labels {
        grid-template-columns: repeat(3, 1fr);
        gap: 4mm;
        padding: 10mm;
        color: #000;
      }
      .shelf-label {
        display: flex;
        flex-direction: column;
        justify-content: center;
        height: 36mm;
        padding: 3mm 4mm;
        border: 0.4mm solid #000;
        border-radius: 2mm;
        break-inside: avoid;
      }
      .shelf-code {
        font-size: 30pt;
        font-weight: 800;
        line-height: 1;
      }
      .shelf-name {
        margin-top: 2mm;
        font-size: 11pt;
        font-weight: 600;
      }
      .shelf-kind {
        margin-top: 1mm;
        font-size: 8pt;
      }
    }
  `,
})
export class LocationsPage {
  private readonly http = inject(HttpClient);
  private readonly permissions = inject(PermissionService);
  private readonly toasts = inject(ToastService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/inventory`;
  private readonly detail = viewChild<ElementRef<HTMLElement>>('detail');

  protected readonly kinds = LOCATION_KINDS;
  protected readonly kindInfo = kindInfo;
  protected readonly codeMax = LOCATION_CODE_MAX;
  protected readonly locations = signal<StorageLocation[]>([]);
  protected readonly showInactive = signal(false);
  protected readonly activeLocations = computed(() => this.locations().filter((l) => l.active));
  protected readonly groups = computed(() =>
    groupByKind(this.showInactive() ? this.locations() : this.activeLocations()),
  );
  protected readonly placedCount = computed(() =>
    this.activeLocations().reduce((sum, l) => sum + l.productCount, 0),
  );
  protected readonly unplacedCount = signal(0);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selected = computed(
    () => this.locations().find((l) => l.id === this.selectedId()) ?? null,
  );
  protected readonly products = signal<LocationProduct[]>([]);
  protected readonly canManage = computed(() => this.permissions.has('STOCK_LOCATION_MANAGE'));
  protected readonly form = signal<LocationForm | null>(null);
  protected readonly deactivating = signal(false);
  protected readonly busy = signal(false);
  protected readonly labels = signal<StorageLocation[]>([]);
  protected reassignTo = '';

  constructor() {
    void this.load();
  }

  protected codePreview(raw: string): string | null {
    return normalizeLocationCode(raw);
  }

  protected async select(location: StorageLocation): Promise<void> {
    this.selectedId.set(location.id);
    this.cancelDeactivation();
    await this.loadProducts(location.id);
    this.detail()?.nativeElement.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  protected startCreate(): void {
    const last = this.locations().reduce((max, l) => Math.max(max, l.sortOrder), 0);
    this.form.set({ id: null, code: '', name: '', kind: 'SHELF', sortOrder: last + 10 });
  }

  protected startEdit(location: StorageLocation): void {
    this.form.set({
      id: location.id,
      code: location.code,
      name: location.name,
      kind: location.kind,
      sortOrder: location.sortOrder,
    });
  }

  protected async save(): Promise<void> {
    const f = this.form();
    const code = f ? normalizeLocationCode(f.code) : null;
    if (!f || !code) {
      return;
    }
    const body = { code, name: f.name.trim(), kind: f.kind, sortOrder: Number(f.sortOrder) || 0 };
    await this.run(async () => {
      const saved = await firstValueFrom(
        f.id
          ? this.http.put<StorageLocation>(`${this.api}/locations/${f.id}`, body)
          : this.http.post<StorageLocation>(`${this.api}/locations`, body),
      );
      this.form.set(null);
      this.toasts.success(f.id ? `${saved.code} mis à jour` : `${saved.code} créé`);
      await this.load();
      if (!f.id) {
        await this.select(saved);
      }
    }, 'Enregistrement refusé');
  }

  protected cancelDeactivation(): void {
    this.deactivating.set(false);
    this.reassignTo = '';
  }

  protected async deactivate(location: StorageLocation): Promise<void> {
    await this.run(async () => {
      await firstValueFrom(
        this.http.put<StorageLocation>(`${this.api}/locations/${location.id}/activation`, {
          active: false,
          reassignTo: this.reassignTo || null,
        }),
      );
      const target = this.locations().find((l) => l.id === this.reassignTo);
      this.toasts.success(
        target
          ? `${location.code} désactivé, produits déplacés vers ${target.code}`
          : `${location.code} désactivé`,
      );
      this.cancelDeactivation();
      await this.load();
      await this.loadProducts(location.id);
    }, 'Désactivation refusée');
  }

  protected async reactivate(location: StorageLocation): Promise<void> {
    await this.run(async () => {
      await firstValueFrom(
        this.http.put(`${this.api}/locations/${location.id}/activation`, { active: true }),
      );
      this.toasts.success(`${location.code} réactivé`);
      await this.load();
    }, 'Réactivation refusée');
  }

  /** Prints an A4 sheet of shelf labels (three per row) through the browser. */
  protected printLabels(locations: StorageLocation[]): void {
    this.labels.set(locations);
    setTimeout(() => window.print());
  }

  private async load(): Promise<void> {
    const [all, unplaced] = await Promise.all([
      firstValueFrom(
        this.http.get<StorageLocation[]>(`${this.api}/locations`, {
          params: { includeInactive: true },
        }),
      ),
      firstValueFrom(
        this.http.get<LocationProduct[]>(`${this.api}/stocks`, {
          params: new HttpParams().set('unassigned', true).set('limit', 500),
        }),
      ),
    ]);
    this.locations.set(all);
    this.unplacedCount.set(unplaced.length);
  }

  private async loadProducts(locationId: string): Promise<void> {
    const products = await firstValueFrom(
      this.http.get<LocationProduct[]>(`${this.api}/stocks`, {
        params: new HttpParams().set('locationId', locationId).set('limit', 500),
      }),
    );
    this.products.set(
      [...products].sort(
        (a, b) =>
          (a.location?.detail ?? '￿').localeCompare(b.location?.detail ?? '￿', 'fr', {
            numeric: true,
          }) || a.designation.localeCompare(b.designation, 'fr'),
      ),
    );
  }

  private async run(action: () => Promise<void>, fallback: string): Promise<void> {
    this.busy.set(true);
    try {
      await action();
    } catch (error) {
      this.toasts.error((error as { error?: { detail?: string } }).error?.detail ?? fallback);
    } finally {
      this.busy.set(false);
    }
  }
}
