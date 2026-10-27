import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Branding, PublicSharing } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { BrandingService } from '../../core/branding/branding.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { contrastRatio, isHexColor, readableTextOn } from '../../core/util/color';
import { ToastService } from '../../core/ui/toast.service';
import { Icon } from '../../shared/ui/icon';
import { LogoFileError, prepareLogo } from '../../shared/ui/logo-file';
import { CartLine } from '../pos/cart.store';
import { Receipt, ReceiptData } from '../pos/receipt';

const SAMPLE_LINE: CartLine = {
  productId: 'sample',
  designation: 'PARACETAMOL 1G CPR B/8',
  cip13: '3400930000001',
  listing: 'NONE',
  vatRate: 2.1,
  unitPriceCents: 216,
  quantity: 2,
  discountCents: 0,
  availableQuantity: 10,
};

/** White-label settings with a live preview of the colors and of the receipt. */
@Component({
  selector: 'app-branding-settings-page',
  imports: [FormsModule, Receipt, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Identité visuelle</h1>
          <p class="page-subtitle">
            Nom, coordonnées, couleurs et ticket de caisse de la pharmacie.
          </p>
        </div>
        <a class="btn btn-secondary" href="/public" target="_blank" rel="noopener">
          <app-icon name="globe" [size]="16" />
          Voir l'annuaire public
        </a>
      </header>

      <div class="max-w-6xl space-y-8">
        <section class="settings-row" id="logo">
          <div>
            <h2 class="settings-title">Logo</h2>
            <p class="settings-text">
              Affiché dans la barre latérale, sur l'écran de connexion, l'onglet du navigateur,
              l'annuaire public et en tête des tickets.
            </p>
          </div>
          <div class="card overflow-hidden">
            <div class="card-body grid gap-5 sm:grid-cols-[9rem_minmax(0,1fr)] sm:items-center">
              <div class="logo-stage" [class.is-empty]="!service.hasLogo()">
                @if (service.logoUrl(); as logo) {
                  <img [src]="logo" alt="Logo actuel de la pharmacie" />
                } @else {
                  <span class="logo-initials">{{ initials() }}</span>
                }
              </div>
              <div
                class="dropzone"
                [class.is-dragging]="dragging()"
                [class.is-busy]="logoBusy()"
                (dragover)="onDragOver($event)"
                (dragleave)="dragging.set(false)"
                (drop)="onDrop($event)"
              >
                <span class="dropzone-icon">
                  <app-icon
                    [name]="logoBusy() ? 'refresh' : 'import'"
                    [size]="20"
                    [class.spin]="logoBusy()"
                  />
                </span>
                <span class="min-w-0 text-sm">
                  <span class="block font-medium">
                    @if (logoBusy()) {
                      Préparation et envoi du logo…
                    } @else {
                      Glissez votre logo ici ou
                      <span class="text-brand">choisissez un fichier</span>
                    }
                  </span>
                  <span class="block text-xs text-subtle">
                    PNG, JPEG, WebP ou SVG · les grandes images sont réduites automatiquement
                  </span>
                </span>
                <input
                  #logoInput
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/svg+xml,image/gif"
                  aria-label="Choisir le logo de la pharmacie"
                  [disabled]="logoBusy()"
                  (change)="onFilePicked($event)"
                />
              </div>
            </div>
            <div class="grid gap-3 border-t border-line bg-surface-2 p-4 sm:grid-cols-3">
              <figure class="preview">
                <figcaption>Barre latérale</figcaption>
                <div
                  class="flex items-center gap-2.5 rounded-lg border border-line bg-surface p-2.5"
                >
                  <span class="preview-mark">
                    @if (service.logoUrl(); as logo) {
                      <img [src]="logo" alt="" class="h-full w-full object-contain" />
                    } @else {
                      {{ initials() }}
                    }
                  </span>
                  <span class="min-w-0">
                    <span class="block truncate text-sm font-semibold">{{
                      draft().displayName
                    }}</span>
                    <span class="block text-xs text-muted">PharmaCore</span>
                  </span>
                </div>
              </figure>
              <figure class="preview">
                <figcaption>Écran de connexion</figcaption>
                <div class="preview-hero">
                  <span class="preview-mark !bg-white/95">
                    @if (service.logoUrl(); as logo) {
                      <img [src]="logo" alt="" class="h-full w-full object-contain" />
                    } @else {
                      <span class="text-brand">{{ initials() }}</span>
                    }
                  </span>
                  <span class="truncate text-sm font-semibold">{{ draft().displayName }}</span>
                </div>
              </figure>
              <figure class="preview">
                <figcaption>Ticket (impression noir et blanc)</figcaption>
                <div
                  class="flex h-[3.75rem] items-center justify-center rounded-lg border border-line bg-white"
                >
                  @if (service.logoUrl(); as logo) {
                    <img
                      [src]="logo"
                      alt=""
                      class="max-h-11 max-w-[70%] object-contain grayscale"
                    />
                  } @else {
                    <span class="text-xs text-neutral-500">Sans logo : le nom seul</span>
                  }
                </div>
              </figure>
            </div>
            @if (service.hasLogo()) {
              <div class="card-footer">
                <span class="text-xs text-muted">Un nouveau fichier remplace le logo actuel.</span>
                <button
                  type="button"
                  class="btn btn-ghost btn-sm ml-auto text-[var(--danger-strong)]"
                  [disabled]="logoBusy()"
                  (click)="removeLogo()"
                >
                  <app-icon name="trash" [size]="14" />
                  Retirer le logo
                </button>
              </div>
            }
          </div>
        </section>

        <form class="space-y-8" (ngSubmit)="save()">
          <section class="settings-row">
            <div>
              <h2 class="settings-title">Identité de la pharmacie</h2>
              <p class="settings-text">
                Affichée dans l'application, sur les tickets, les factures et l'annuaire public.
              </p>
            </div>
            <div class="card card-body grid gap-4 sm:grid-cols-2">
              <label class="block sm:col-span-2"
                ><span class="label">Nom affiché</span>
                <input
                  class="input"
                  name="displayName"
                  required
                  maxlength="150"
                  [ngModel]="draft().displayName"
                  (ngModelChange)="patch({ displayName: $event })"
              /></label>
              <label class="block"
                ><span class="label">Adresse</span
                ><input
                  class="input"
                  name="a1"
                  [ngModel]="draft().addressLine1"
                  (ngModelChange)="patch({ addressLine1: $event })"
              /></label>
              <label class="block"
                ><span class="label">Complément</span
                ><input
                  class="input"
                  name="a2"
                  [ngModel]="draft().addressLine2"
                  (ngModelChange)="patch({ addressLine2: $event })"
              /></label>
              <label class="block"
                ><span class="label">Code postal</span
                ><input
                  class="input"
                  name="cp"
                  [ngModel]="draft().postalCode"
                  (ngModelChange)="patch({ postalCode: $event })"
              /></label>
              <label class="block"
                ><span class="label">Ville</span
                ><input
                  class="input"
                  name="city"
                  [ngModel]="draft().city"
                  (ngModelChange)="patch({ city: $event })"
              /></label>
              <label class="block"
                ><span class="label">Téléphone</span
                ><input
                  class="input"
                  name="phone"
                  [ngModel]="draft().phone"
                  (ngModelChange)="patch({ phone: $event })"
              /></label>
              <label class="block"
                ><span class="label">E-mail</span
                ><input
                  class="input"
                  type="email"
                  name="email"
                  [ngModel]="draft().email"
                  (ngModelChange)="patch({ email: $event })"
              /></label>
              <div class="divider sm:col-span-2"></div>
              <label class="block"
                ><span class="label">SIRET</span
                ><input
                  class="input"
                  name="siret"
                  maxlength="30"
                  [ngModel]="draft().legalIdentifier"
                  (ngModelChange)="patch({ legalIdentifier: $event })"
              /></label>
              <label class="block"
                ><span class="label">N° FINESS</span
                ><input
                  class="input"
                  name="finess"
                  maxlength="9"
                  [ngModel]="draft().finessNumber"
                  (ngModelChange)="patch({ finessNumber: $event })"
              /></label>
              <label class="block"
                ><span class="label">Préfixe ordonnancier</span
                ><input
                  class="input"
                  name="ord"
                  maxlength="10"
                  [ngModel]="draft().ordonnancierPrefix"
                  (ngModelChange)="patch({ ordonnancierPrefix: $event })"
              /></label>
            </div>
          </section>

          <section class="settings-row">
            <div>
              <h2 class="settings-title">Couleurs</h2>
              <p class="settings-text">
                Appliquées en direct à l'interface pour juger du rendu avant d'enregistrer.
              </p>
            </div>
            <div class="card card-body space-y-4">
              <div class="grid gap-3 sm:grid-cols-3">
                @for (color of colors; track color.key) {
                  <label class="swatch">
                    <input
                      type="color"
                      [name]="color.key"
                      [ngModel]="draft()[color.key]"
                      (ngModelChange)="setColor(color.key, $event)"
                    />
                    <span class="min-w-0">
                      <span class="block text-sm font-medium">{{ color.label }}</span>
                      <code class="text-xs text-muted">{{ draft()[color.key] }}</code>
                    </span>
                  </label>
                }
              </div>
              <p
                class="alert"
                [class.alert-success]="contrast() >= 4.5"
                [class.alert-danger]="contrast() < 4.5"
              >
                <app-icon [name]="contrast() < 4.5 ? 'alert' : 'checkCircle'" [size]="18" />
                <span>
                  Contraste du texte sur la couleur principale : {{ contrast().toFixed(1) }}:1
                  {{ contrast() < 4.5 ? '(insuffisant, WCAG AA = 4.5)' : '(conforme WCAG AA)' }}
                </span>
              </p>
              <div class="flex flex-wrap items-center gap-2" aria-hidden="true">
                <span class="btn btn-primary btn-sm">Bouton principal</span>
                <span class="badge badge-brand"><span class="dot"></span>Pastille</span>
                <span class="badge text-ink">
                  <span class="dot" [style.background]="draft().accentColor"></span>Accent
                </span>
              </div>
            </div>
          </section>

          <section class="settings-row">
            <div>
              <h2 class="settings-title">Ticket de caisse</h2>
              <p class="settings-text">En-tête et pied de page imprimés sur chaque ticket.</p>
            </div>
            <div class="card grid overflow-hidden lg:grid-cols-[minmax(0,1fr)_auto]">
              <div class="card-body space-y-4">
                <label class="block"
                  ><span class="label">En-tête</span
                  ><textarea
                    class="textarea"
                    name="header"
                    maxlength="500"
                    rows="2"
                    [ngModel]="draft().receiptHeader"
                    (ngModelChange)="patch({ receiptHeader: $event })"
                  ></textarea>
                </label>
                <label class="block"
                  ><span class="label">Pied de page</span
                  ><textarea
                    class="textarea"
                    name="footer"
                    maxlength="500"
                    rows="2"
                    [ngModel]="draft().receiptFooter"
                    (ngModelChange)="patch({ receiptFooter: $event })"
                  ></textarea>
                </label>
                <p class="flex items-center gap-2 text-xs text-muted">
                  <app-icon name="image" [size]="14" />
                  Le logo choisi
                  <a
                    href="#logo"
                    class="font-medium text-brand underline"
                    (click)="scrollToLogo($event)"
                    >en haut de la page</a
                  >
                  s'imprime en tête des tickets 80 mm et des factures A4.
                </p>
              </div>
              <div
                class="flex flex-col items-center gap-3 border-t border-line bg-surface-3 p-5 lg:border-t-0 lg:border-l"
              >
                <p class="flex items-center gap-1.5 text-xs font-semibold text-muted">
                  <app-icon name="receipt" [size]="14" />
                  Aperçu du ticket
                </p>
                <app-receipt [data]="sample" class="block shadow-[var(--shadow-lg)]" />
              </div>
            </div>
          </section>

          <div class="save-bar">
            <p class="hidden min-w-0 items-center gap-2 text-sm text-muted sm:flex">
              <app-icon name="info" [size]="16" />
              Aperçu en direct : les modifications s'appliquent à l'enregistrement.
            </p>
            <div class="flex flex-1 justify-end gap-2 sm:flex-none">
              <button type="button" class="btn btn-ghost" (click)="reset()">
                Annuler<span class="hidden sm:inline">les modifications</span>
              </button>
              <button type="submit" class="btn btn-primary">
                <app-icon name="check" [size]="16" />
                Enregistrer
              </button>
            </div>
          </div>
        </form>

        <form
          class="settings-row"
          (ngSubmit)="saveSharing()"
          *appHasPermission="['DIRECTORY_SHARING_MANAGE', 'CONFIG_BRANDING_MANAGE']"
        >
          <div>
            <h2 class="settings-title">Annuaire public</h2>
            <p class="settings-text">
              Les patients trouvent la pharmacie, ses horaires et, si vous l'acceptez, un niveau de
              disponibilité (« disponible » ou « dernières unités », jamais la quantité ni le prix)
              sur la page publique
              <a
                class="font-medium text-brand underline"
                href="/public"
                target="_blank"
                rel="noopener"
                >/public</a
              >.
            </p>
          </div>
          <div class="card overflow-hidden">
            <div class="divide-y divide-[var(--border)]">
              <label class="switch-row">
                <span>
                  <span class="block text-sm font-medium"
                    >Afficher la pharmacie dans l'annuaire public</span
                  >
                  <span class="block text-xs text-muted"
                    >Nom, adresse, horaires et téléphone visibles sans compte.</span
                  >
                </span>
                <input
                  type="checkbox"
                  role="switch"
                  class="toggle"
                  name="publicListing"
                  [ngModel]="sharing().publicListing"
                  (ngModelChange)="patchSharing({ publicListing: $event })"
                />
              </label>
              <label class="switch-row">
                <span>
                  <span class="block text-sm font-medium"
                    >Publier le niveau de disponibilité de mes produits</span
                  >
                  <span class="block text-xs text-muted"
                    >Nécessite l'affichage dans l'annuaire public.</span
                  >
                </span>
                <input
                  type="checkbox"
                  role="switch"
                  class="toggle"
                  name="publicAvailability"
                  [disabled]="!sharing().publicListing"
                  [ngModel]="sharing().publicAvailability"
                  (ngModelChange)="patchSharing({ publicAvailability: $event })"
                />
              </label>
            </div>
            <div class="card-body grid gap-4 border-t border-line sm:grid-cols-2">
              <label class="block"
                ><span class="label">Horaires d'ouverture</span
                ><input
                  class="input"
                  name="openingHours"
                  maxlength="300"
                  placeholder="Lun-Sam 8h-22h, Dim 9h-13h"
                  [ngModel]="sharing().openingHours"
                  (ngModelChange)="patchSharing({ openingHours: $event })"
              /></label>
              <label class="block"
                ><span class="label">Numéro WhatsApp</span
                ><input
                  class="input"
                  name="whatsapp"
                  maxlength="30"
                  placeholder="+227 90 00 00 00"
                  [ngModel]="sharing().whatsapp"
                  (ngModelChange)="patchSharing({ whatsapp: $event })"
              /></label>
              <label class="block"
                ><span class="label">Latitude</span
                ><input
                  class="input"
                  name="latitude"
                  type="number"
                  step="0.000001"
                  min="-90"
                  max="90"
                  [ngModel]="sharing().latitude"
                  (ngModelChange)="patchSharing({ latitude: toNumber($event) })"
              /></label>
              <label class="block"
                ><span class="label">Longitude</span
                ><input
                  class="input"
                  name="longitude"
                  type="number"
                  step="0.000001"
                  min="-180"
                  max="180"
                  [ngModel]="sharing().longitude"
                  (ngModelChange)="patchSharing({ longitude: toNumber($event) })"
              /></label>
              <div class="sm:col-span-2">
                <button type="button" class="btn btn-secondary btn-sm" (click)="locate()">
                  <app-icon name="locate" [size]="16" />
                  Utiliser ma position
                </button>
              </div>
            </div>
            <div class="card-footer">
              <button
                type="submit"
                class="btn btn-primary ml-auto"
                *appHasPermission="'DIRECTORY_SHARING_MANAGE'"
              >
                <app-icon name="check" [size]="16" />
                Enregistrer l'annuaire public
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  `,
  styles: `
    .settings-row {
      display: grid;
      gap: 1rem;
    }
    @media (min-width: 768px) {
      .settings-row {
        grid-template-columns: 15rem minmax(0, 1fr);
        gap: 2rem;
      }
    }
    .settings-title {
      font-size: 0.95rem;
      font-weight: 650;
    }
    .settings-text {
      margin-top: 0.25rem;
      font-size: 0.8125rem;
      line-height: 1.5;
      color: var(--text-muted);
    }
    .swatch {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.6rem;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      cursor: pointer;
      transition: background-color 120ms ease;
    }
    .swatch:hover {
      background: var(--surface-2);
    }
    .swatch input[type='color'] {
      width: 2.5rem;
      height: 2.5rem;
      flex-shrink: 0;
      padding: 0;
      border: 0;
      background: transparent;
      cursor: pointer;
    }
    .swatch input[type='color']::-webkit-color-swatch-wrapper {
      padding: 0;
    }
    .swatch input[type='color']::-webkit-color-swatch {
      border: 1px solid var(--border-strong);
      border-radius: 0.6rem;
    }
    .swatch input[type='color']::-moz-color-swatch {
      border: 1px solid var(--border-strong);
      border-radius: 0.6rem;
    }
    .dropzone {
      position: relative;
      display: flex;
      align-items: center;
      gap: 0.85rem;
      padding: 1rem;
      border: 1.5px dashed var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface-2);
      transition:
        border-color 120ms ease,
        background-color 120ms ease;
    }
    .dropzone:hover,
    .dropzone.is-dragging,
    .dropzone:has(input:focus-visible) {
      border-color: var(--brand-primary);
      background: var(--brand-softer);
    }
    .dropzone input {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      opacity: 0;
      cursor: pointer;
    }
    .dropzone.is-dragging {
      border-style: solid;
      transform: scale(1.01);
    }
    .dropzone.is-busy {
      opacity: 0.75;
      pointer-events: none;
    }
    .dropzone {
      min-height: 7rem;
      transition:
        border-color 120ms ease,
        background-color 120ms ease,
        transform 120ms ease;
    }
    .spin {
      animation: spin 0.9s linear infinite;
    }
    @keyframes spin {
      to {
        transform: rotate(360deg);
      }
    }
    .logo-stage {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 9rem;
      height: 9rem;
      margin-inline: auto;
      padding: 0.9rem;
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      /* Checkerboard: shows whether the logo has a transparent background */
      background: repeating-conic-gradient(var(--surface-3) 0% 25%, var(--surface) 0% 50%) 50% /
        16px 16px;
    }
    .logo-stage img {
      max-width: 100%;
      max-height: 100%;
      object-fit: contain;
    }
    .logo-stage.is-empty {
      background: var(--brand-primary);
      border-color: transparent;
    }
    .logo-initials {
      font-size: 2.5rem;
      font-weight: 750;
      letter-spacing: -0.03em;
      color: var(--brand-on-primary);
    }
    .preview {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
      min-width: 0;
    }
    .preview figcaption {
      font-size: 0.7rem;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--text-subtle);
    }
    .preview-mark {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.25rem;
      height: 2.25rem;
      flex-shrink: 0;
      overflow: hidden;
      padding: 0.2rem;
      border-radius: 0.6rem;
      background: var(--brand-primary);
      color: var(--brand-on-primary);
      font-size: 0.75rem;
      font-weight: 700;
    }
    .preview-hero {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      height: 3.75rem;
      padding: 0 0.75rem;
      border-radius: 0.5rem;
      color: var(--brand-on-primary);
      background: linear-gradient(
        150deg,
        var(--brand-primary),
        color-mix(in oklab, var(--brand-primary) 50%, black)
      );
    }
    .dropzone-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 3rem;
      height: 3rem;
      flex-shrink: 0;
      border: 1px solid var(--border);
      border-radius: 0.75rem;
      background: var(--surface);
      color: var(--text-subtle);
      box-shadow: var(--shadow-sm);
    }
    .save-bar {
      position: sticky;
      bottom: 1rem;
      z-index: 5;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.75rem 1rem;
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: color-mix(in oklab, var(--surface) 92%, transparent);
      backdrop-filter: blur(8px);
      box-shadow: var(--shadow-lg);
    }
    .switch-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      padding: calc(1rem * var(--density)) calc(1.25rem * var(--density));
      cursor: pointer;
    }
    .card-footer {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.75rem;
      padding: 0.75rem calc(1.25rem * var(--density));
      border-top: 1px solid var(--border);
      background: var(--surface-2);
    }
    .toggle {
      appearance: none;
      position: relative;
      flex-shrink: 0;
      width: 2.25rem !important;
      height: 1.3rem !important;
      border-radius: 999px;
      background: var(--border-strong);
      transition: background-color 150ms ease;
      cursor: pointer;
    }
    .toggle::after {
      content: '';
      position: absolute;
      top: 0.15rem;
      left: 0.15rem;
      width: 1rem;
      height: 1rem;
      border-radius: 999px;
      background: #fff;
      box-shadow: 0 1px 2px rgb(0 0 0 / 0.25);
      transition: transform 150ms ease;
    }
    .toggle:checked {
      background: var(--brand-primary);
    }
    .toggle:checked::after {
      transform: translateX(0.95rem);
    }
    .toggle:disabled {
      cursor: not-allowed;
      opacity: 0.45;
    }
  `,
})
export class BrandingSettingsPage {
  protected readonly service = inject(BrandingService);
  private readonly http = inject(HttpClient);
  private readonly sharingApi = `${inject(APP_ENVIRONMENT).apiUrl}/v1/config/public-sharing`;
  protected readonly sharing = signal<PublicSharing>({
    publicListing: false,
    publicAvailability: false,
    latitude: null,
    longitude: null,
    openingHours: null,
    whatsapp: null,
  });
  protected readonly draft = signal<Branding>({ ...this.service.branding() });
  private readonly toasts = inject(ToastService);
  protected readonly logoBusy = signal(false);
  protected readonly dragging = signal(false);
  protected readonly initials = computed(() => initialsOf(this.draft().displayName));
  protected readonly colors = [
    { key: 'primaryColor', label: 'Principale' },
    { key: 'secondaryColor', label: 'Secondaire' },
    { key: 'accentColor', label: 'Accent' },
  ] as const;
  protected readonly contrast = computed(() => {
    const primary = this.draft().primaryColor;
    return isHexColor(primary) ? contrastRatio(primary, readableTextOn(primary)) : 0;
  });
  protected readonly sample: ReceiptData = {
    ticketNumber: 'T0001-000042',
    terminalId: 'T0001',
    occurredAt: new Date(),
    cashier: 'Aperçu',
    lines: [SAMPLE_LINE],
    payments: [{ method: 'CASH', amountCents: 500 }],
    grossCents: 432,
    discountCents: 0,
    netCents: 432,
    changeDueCents: 68,
    vat: [{ rate: 2.1, totalTtc: 432, vat: 9 }],
    offline: false,
  };

  constructor() {
    void this.loadSharing();
  }

  protected patchSharing(change: Partial<PublicSharing>): void {
    this.sharing.update((current) => ({ ...current, ...change }));
  }

  protected toNumber(value: unknown): number | null {
    const n = Number(value);
    return value === '' || value === null || Number.isNaN(n) ? null : n;
  }

  protected locate(): void {
    if (!('geolocation' in navigator)) {
      this.toasts.error('Géolocalisation indisponible sur ce poste');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) =>
        this.patchSharing({
          latitude: Number(position.coords.latitude.toFixed(6)),
          longitude: Number(position.coords.longitude.toFixed(6)),
        }),
      () => this.toasts.error('Position refusée ou indisponible'),
      { timeout: 10_000 },
    );
  }

  protected async saveSharing(): Promise<void> {
    try {
      const saved = await firstValueFrom(
        this.http.put<PublicSharing>(this.sharingApi, this.sharing()),
      );
      this.sharing.set(saved);
      this.toasts.success(
        saved.publicListing
          ? 'Annuaire public enregistré : la pharmacie est visible des patients'
          : 'Annuaire public enregistré : la pharmacie reste privée',
      );
    } catch (error) {
      this.toasts.error(
        (error as { error?: { detail?: string } }).error?.detail ??
          'Enregistrement impossible : vérifiez les champs',
      );
    }
  }

  private async loadSharing(): Promise<void> {
    try {
      this.sharing.set(await firstValueFrom(this.http.get<PublicSharing>(this.sharingApi)));
    } catch {
      // Keep the defaults when offline or without the right
    }
  }

  protected patch(change: Partial<Branding>): void {
    this.draft.update((current) => ({ ...current, ...change }));
    this.service.previewChanges(this.draft());
  }

  protected setColor(key: 'primaryColor' | 'secondaryColor' | 'accentColor', value: string): void {
    this.patch({ [key]: value.toUpperCase() });
  }

  protected reset(): void {
    this.service.previewChanges(null);
    this.draft.set({ ...this.service.branding() });
  }

  protected async save(): Promise<void> {
    try {
      await this.service.save(this.draft());
      this.toasts.success('Identité enregistrée');
    } catch {
      this.toasts.error('Enregistrement impossible : vérifiez les champs');
    }
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      void this.uploadLogo(file);
    }
  }

  protected onFilePicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) {
      void this.uploadLogo(file);
    }
  }

  protected scrollToLogo(event: Event): void {
    event.preventDefault();
    document.getElementById('logo')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  protected async removeLogo(): Promise<void> {
    this.logoBusy.set(true);
    try {
      await this.service.removeLogo();
      this.toasts.success('Logo retiré : les initiales le remplacent');
    } catch {
      this.toasts.error('Le logo n’a pas pu être retiré');
    } finally {
      this.logoBusy.set(false);
    }
  }

  private async uploadLogo(file: File): Promise<void> {
    if (this.logoBusy()) {
      return;
    }
    this.logoBusy.set(true);
    try {
      await this.service.uploadLogo(await prepareLogo(file));
      this.toasts.success('Logo mis à jour');
    } catch (error) {
      this.toasts.error(
        error instanceof LogoFileError
          ? error.message
          : ((error as { error?: { detail?: string } }).error?.detail ??
              'Envoi du logo impossible : vérifiez la connexion'),
      );
    } finally {
      this.logoBusy.set(false);
    }
  }
}

function initialsOf(name: string): string {
  const words = name
    .replace(/^(pharmacie|officine)\s+(du|de la|de l'|des|de)?\s*/i, '')
    .split(/\s+/)
    .filter(Boolean);
  return (
    words.length > 1 ? words[0][0] + words[1][0] : (words[0] ?? 'P').slice(0, 2)
  ).toUpperCase();
}
