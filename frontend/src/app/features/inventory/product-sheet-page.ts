import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ProductSheet } from '../../core/api/models';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { LocationChip } from '../../shared/ui/location-chip';
import { ImageFileError, prepareProductPhoto } from '../../shared/ui/logo-file';
import { ProductImageService } from '../../shared/ui/product-image.service';
import { ProductThumb } from '../../shared/ui/product-thumb';

const LISTING_LABELS: Record<string, string> = {
  NONE: 'Hors liste',
  LIST_I: 'Liste I',
  LIST_II: 'Liste II',
  STUPEFIANT: 'Stupéfiant',
};

/**
 * Product sheet of the pharmacy, reached from the stock list: the box photo in large (taken
 * with the phone camera, chosen or dropped, then cropped and reduced on the terminal), the
 * price, quantities, placement and lots on the shelf.
 */
@Component({
  selector: 'app-product-sheet-page',
  imports: [RouterLink, DatePipe, MoneyPipe, Icon, LocationChip, ProductThumb],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <a routerLink="/inventory" class="back-link">
        <app-icon name="chevronLeft" [size]="16" />
        Stocks
      </a>

      @if (sheet(); as p) {
        <header class="page-header">
          <div class="min-w-0">
            <h1 class="page-title break-words">{{ p.designation }}</h1>
            <p class="page-subtitle">
              <span class="tabular-nums">CIP {{ p.cip13 }}</span>
              @if (p.dci) {
                · {{ p.dci }}
              }
              @if (p.pharmaceuticalForm || p.dosage) {
                · {{ p.pharmaceuticalForm }} {{ p.dosage }}
              }
            </p>
          </div>
          <div class="flex flex-wrap gap-2">
            @if (p.listing !== 'NONE') {
              <span class="badge badge-danger">{{ listingLabel(p.listing) }}</span>
            }
            @if (!p.active) {
              <span class="badge badge-warning"
                ><span class="dot"></span>Retiré du référentiel</span
              >
            } @else if (p.available <= 0) {
              <span class="badge badge-danger"><span class="dot"></span>Rupture</span>
            } @else {
              <span class="badge badge-success"><span class="dot"></span>En stock</span>
            }
          </div>
        </header>

        <div class="grid gap-5 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
          <section class="card self-start" aria-labelledby="photo-title">
            <div class="card-header">
              <h2 id="photo-title" class="card-title">Photo de la boîte</h2>
              @if (p.imageSizeBytes) {
                <span class="badge">{{ kilobytes(p.imageSizeBytes) }} Ko</span>
              }
            </div>
            <div class="card-body">
              <div
                class="photo-stage"
                [class.is-dragging]="dragging()"
                [class.is-busy]="busy()"
                (dragover)="onDragOver($event)"
                (dragleave)="dragging.set(false)"
                (drop)="onDrop($event)"
                data-testid="product-photo"
              >
                <app-product-thumb
                  [productId]="p.productId"
                  [version]="p.imageVersion"
                  [name]="p.designation"
                  [form]="p.pharmaceuticalForm"
                  [size]="232"
                />
                @if (busy()) {
                  <span class="photo-overlay">
                    <app-icon name="refresh" [size]="22" class="spin" />
                    Préparation de la photo…
                  </span>
                } @else if (dragging()) {
                  <span class="photo-overlay">
                    <app-icon name="import" [size]="22" />
                    Déposez la photo
                  </span>
                }
              </div>

              @if (canEdit()) {
                <div class="mt-4 grid gap-2" [class.sm:grid-cols-2]="touch">
                  @if (touch) {
                    <label class="btn btn-primary file-button" [class.is-disabled]="busy()">
                      <app-icon name="camera" [size]="16" />
                      Prendre une photo
                      <input
                        type="file"
                        accept="image/*"
                        capture="environment"
                        aria-label="Prendre une photo de la boîte"
                        [disabled]="busy()"
                        (change)="onPicked($event)"
                      />
                    </label>
                  }
                  <label
                    class="btn file-button"
                    [class.btn-primary]="!touch"
                    [class.btn-secondary]="touch"
                    [class.is-disabled]="busy()"
                  >
                    <app-icon name="image" [size]="16" />
                    {{ p.imageVersion ? 'Changer la photo' : 'Choisir une photo' }}
                    <input
                      #fileInput
                      type="file"
                      accept="image/jpeg,image/png,image/webp,image/*"
                      aria-label="Choisir une photo de la boîte"
                      [disabled]="busy()"
                      (change)="onPicked($event)"
                    />
                  </label>
                </div>
                <p class="hint text-center">{{ photoHint }}</p>
                @if (p.imageVersion) {
                  <div class="mt-3 flex justify-center border-t border-line pt-3">
                    <button
                      type="button"
                      class="btn btn-ghost btn-sm text-[var(--danger-strong)]"
                      [disabled]="busy()"
                      (click)="remove(p)"
                    >
                      <app-icon name="trash" [size]="14" />
                      Retirer la photo
                    </button>
                  </div>
                }
              } @else if (!p.imageVersion) {
                <p class="hint text-center">
                  Aucune photo : le titulaire ou le gestionnaire du stock peut en ajouter une.
                </p>
              }
            </div>
          </section>

          <div class="min-w-0 space-y-5">
            <div class="grid grid-cols-2 gap-4 xl:grid-cols-4">
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>
                  Prix de vente
                </div>
                <div class="kpi-value">{{ p.sellingPriceCents ?? p.publicPriceCents | money }}</div>
                <div class="kpi-foot">
                  {{
                    p.sellingPriceCents === null
                      ? 'Prix public du référentiel'
                      : 'Prix de la pharmacie'
                  }}
                </div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="stock" [size]="16" /></span>
                  Disponible
                </div>
                <div class="kpi-value" [class.text-danger]="p.available <= 0">
                  {{ p.available }}
                </div>
                <div class="kpi-foot">{{ p.onHand }} en rayon · {{ p.reserved }} réservé(s)</div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="calendar" [size]="16" /></span>
                  Prochaine péremption
                </div>
                <div class="kpi-value text-[1.35rem]">
                  @if (nextExpiry(); as lot) {
                    {{ lot.expiryDate | date: 'MM/yyyy' }}
                  } @else {
                    —
                  }
                </div>
                <div class="kpi-foot">
                  {{ nextExpiry() ? 'Lot ' + nextExpiry()?.lotNumber : 'Aucun lot en rayon' }}
                </div>
              </div>
              <div class="card kpi">
                <div class="kpi-label">
                  <span class="kpi-icon"><app-icon name="trendDown" [size]="16" /></span>
                  Seuil
                </div>
                <div class="kpi-value">{{ p.reorderThreshold }}</div>
                <div class="kpi-foot">Réassort de {{ p.reorderQuantity }}</div>
              </div>
            </div>

            <section class="card">
              <div class="card-header"><h2 class="card-title">Informations</h2></div>
              <dl class="facts card-body">
                <div>
                  <dt>Emplacement</dt>
                  <dd>
                    @if (p.location; as place) {
                      <app-location-chip [place]="place" />
                    } @else {
                      <span class="text-subtle">Non rangé</span>
                    }
                  </dd>
                </div>
                <div>
                  <dt>DCI</dt>
                  <dd>{{ p.dci ?? '—' }}</dd>
                </div>
                <div>
                  <dt>Forme et dosage</dt>
                  <dd>{{ formAndDosage(p) }}</dd>
                </div>
                <div>
                  <dt>Laboratoire</dt>
                  <dd>{{ p.laboratory ?? '—' }}</dd>
                </div>
                <div>
                  <dt>Liste</dt>
                  <dd>{{ listingLabel(p.listing) }}</dd>
                </div>
                <div>
                  <dt>TVA</dt>
                  <dd class="tabular-nums">{{ p.vatRate }} %</dd>
                </div>
                <div>
                  <dt>Code EAN</dt>
                  <dd class="tabular-nums">{{ p.ean13 ?? '—' }}</dd>
                </div>
                <div>
                  <dt>Prix public</dt>
                  <dd>{{ p.publicPriceCents | money }}</dd>
                </div>
              </dl>
            </section>

            <section class="card">
              <div class="card-header">
                <h2 class="card-title">Lots en rayon</h2>
                <span class="badge">{{ p.lots.length }} lot(s)</span>
              </div>
              <div class="overflow-x-auto">
                <table class="data-table">
                  <thead>
                    <tr>
                      <th>Lot</th>
                      <th>Péremption</th>
                      <th class="num text-right">Qté</th>
                      <th>Statut</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (lot of p.lots; track lot.lotId) {
                      <tr>
                        <td class="font-mono text-xs">{{ lot.lotNumber }}</td>
                        <td class="whitespace-nowrap">{{ lot.expiryDate | date: 'dd/MM/yyyy' }}</td>
                        <td class="num">{{ lot.quantity }}</td>
                        <td>
                          @if (lot.expired) {
                            <span class="badge badge-danger"><span class="dot"></span>Périmé</span>
                          } @else {
                            <span class="badge badge-success"
                              ><span class="dot"></span>Vendable</span
                            >
                          }
                        </td>
                      </tr>
                    } @empty {
                      <tr>
                        <td colspan="4">
                          <div class="empty-state">
                            <app-icon name="stock" [size]="22" />
                            <p>Aucun lot en rayon pour ce produit.</p>
                          </div>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        </div>
      } @else if (failed()) {
        <div class="empty-state mt-6">
          <app-icon name="alert" [size]="22" />
          <p>Fiche introuvable ou serveur injoignable.</p>
        </div>
      } @else {
        <div class="mt-6 grid gap-5 lg:grid-cols-[22rem_minmax(0,1fr)]">
          <div class="skeleton h-80"></div>
          <div class="skeleton h-80"></div>
        </div>
      }
    </div>
  `,
  styles: `
    .back-link {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      margin-bottom: 0.75rem;
      font-size: 0.8125rem;
      font-weight: 600;
      color: var(--text-muted);
    }
    .back-link:hover {
      color: var(--brand-text);
    }
    .photo-stage {
      position: relative;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0.75rem;
      border: 1.5px dashed var(--border-strong);
      border-radius: var(--radius-lg);
      background: var(--surface-2);
      transition:
        border-color 120ms ease,
        background-color 120ms ease;
    }
    .photo-stage.is-dragging {
      border-color: var(--brand-primary);
      background: var(--brand-softer);
    }
    .photo-stage app-product-thumb {
      max-width: 100%;
      height: auto;
      aspect-ratio: 1;
      box-shadow: var(--shadow);
    }
    .photo-overlay {
      position: absolute;
      inset: 0.75rem;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      border-radius: var(--radius);
      background: color-mix(in oklab, var(--surface) 82%, transparent);
      color: var(--text);
      font-size: 0.875rem;
      font-weight: 600;
      backdrop-filter: blur(2px);
    }
    .file-button {
      position: relative;
      overflow: hidden;
      cursor: pointer;
    }
    .file-button input {
      position: absolute;
      inset: 0;
      opacity: 0;
      cursor: pointer;
    }
    .file-button.is-disabled {
      opacity: 0.55;
      pointer-events: none;
    }
    .file-button:has(input:focus-visible) {
      outline: 2px solid var(--brand-ring);
      outline-offset: 2px;
    }
    .facts {
      display: grid;
      gap: 1rem 1.5rem;
      grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr));
    }
    .facts dt {
      font-size: 0.75rem;
      font-weight: 550;
      color: var(--text-subtle);
    }
    .facts dd {
      margin-top: 0.2rem;
      font-size: 0.875rem;
      font-weight: 550;
    }
    .spin {
      animation: spin 900ms linear infinite;
    }
    @keyframes spin {
      to {
        transform: rotate(360deg);
      }
    }
  `,
})
export class ProductSheetPage {
  readonly productId = input.required<string>();

  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/inventory/products`;
  private readonly images = inject(ProductImageService);
  private readonly permissions = inject(PermissionService);
  private readonly toasts = inject(ToastService);
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

  /** Phones and tablets get the camera button; a desktop picks a file or drops one. */
  protected readonly touch =
    typeof matchMedia === 'function' && matchMedia('(pointer: coarse)').matches;
  protected readonly photoHint = this.touch
    ? 'Recadrée au carré et réduite à 256 px avant l’envoi'
    : 'Ou glissez une photo sur le cadre · recadrée au carré et réduite à 256 px avant l’envoi';
  protected readonly sheet = signal<ProductSheet | null>(null);
  protected readonly failed = signal(false);
  protected readonly busy = signal(false);
  protected readonly dragging = signal(false);
  protected readonly canEdit = computed(() => this.permissions.has('STOCK_PRICE_MANAGE'));
  protected readonly nextExpiry = computed(
    () => this.sheet()?.lots.find((lot) => !lot.expired) ?? null,
  );

  constructor() {
    effect(() => {
      void this.load(this.productId());
    });
  }

  protected listingLabel(listing: string): string {
    return LISTING_LABELS[listing] ?? listing;
  }

  protected formAndDosage(p: ProductSheet): string {
    return [p.pharmaceuticalForm, p.dosage].filter(Boolean).join(' · ') || '—';
  }

  protected kilobytes(bytes: number): number {
    return Math.max(1, Math.round(bytes / 1024));
  }

  protected onDragOver(event: DragEvent): void {
    if (!this.canEdit() || this.busy()) {
      return;
    }
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file && this.canEdit() && !this.busy()) {
      void this.send(file);
    }
  }

  protected onPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      void this.send(file);
    }
  }

  protected async remove(sheet: ProductSheet): Promise<void> {
    this.busy.set(true);
    try {
      await this.images.remove(sheet.productId);
      this.sheet.update((current) =>
        current
          ? { ...current, imageVersion: null, imageSizeBytes: null, imageUpdatedAt: null }
          : current,
      );
      this.toasts.success('Photo retirée : la miniature reprend l’initiale du produit');
    } catch (error) {
      this.toasts.error(detail(error, 'Suppression refusée'));
    } finally {
      this.busy.set(false);
    }
  }

  private async send(file: File): Promise<void> {
    const productId = this.sheet()?.productId;
    if (!productId) {
      return;
    }
    this.busy.set(true);
    try {
      const photo = await prepareProductPhoto(file);
      const view = await this.images.upload(productId, photo);
      this.sheet.update((current) =>
        current
          ? {
              ...current,
              imageVersion: view.imageVersion,
              imageSizeBytes: view.sizeBytes,
              imageUpdatedAt: view.updatedAt,
            }
          : current,
      );
      this.toasts.success('Photo enregistrée : elle s’affiche partout où l’on choisit ce produit');
    } catch (error) {
      this.toasts.error(
        error instanceof ImageFileError ? error.message : detail(error, 'Photo refusée'),
      );
    } finally {
      this.busy.set(false);
      this.fileInput()?.nativeElement.blur();
    }
  }

  private async load(productId: string): Promise<void> {
    this.failed.set(false);
    try {
      this.sheet.set(await firstValueFrom(this.http.get<ProductSheet>(`${this.api}/${productId}`)));
    } catch {
      this.failed.set(true);
    }
  }
}

function detail(error: unknown, fallback: string): string {
  return (error as { error?: { detail?: string } }).error?.detail ?? fallback;
}
