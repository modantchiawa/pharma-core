import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Branding } from '../../core/api/models';
import { BrandingService } from '../../core/branding/branding.service';
import { contrastRatio, isHexColor, readableTextOn } from '../../core/util/color';
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
  imports: [FormsModule, Receipt],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-6 p-4 lg:grid-cols-[1fr_auto]">
      <form
        class="grid max-w-3xl gap-3 rounded-lg border bg-white p-4 md:grid-cols-2"
        (ngSubmit)="save()"
      >
        <h2 class="text-lg font-semibold md:col-span-2">Identité de la pharmacie</h2>
        <label class="md:col-span-2"
          >Nom affiché
          <input
            class="field"
            name="displayName"
            required
            maxlength="150"
            [ngModel]="draft().displayName"
            (ngModelChange)="patch({ displayName: $event })"
        /></label>
        <label
          >Adresse<input
            class="field"
            name="a1"
            [ngModel]="draft().addressLine1"
            (ngModelChange)="patch({ addressLine1: $event })"
        /></label>
        <label
          >Complément<input
            class="field"
            name="a2"
            [ngModel]="draft().addressLine2"
            (ngModelChange)="patch({ addressLine2: $event })"
        /></label>
        <label
          >Code postal<input
            class="field"
            name="cp"
            [ngModel]="draft().postalCode"
            (ngModelChange)="patch({ postalCode: $event })"
        /></label>
        <label
          >Ville<input
            class="field"
            name="city"
            [ngModel]="draft().city"
            (ngModelChange)="patch({ city: $event })"
        /></label>
        <label
          >Téléphone<input
            class="field"
            name="phone"
            [ngModel]="draft().phone"
            (ngModelChange)="patch({ phone: $event })"
        /></label>
        <label
          >E-mail<input
            class="field"
            type="email"
            name="email"
            [ngModel]="draft().email"
            (ngModelChange)="patch({ email: $event })"
        /></label>
        <label
          >SIRET<input
            class="field"
            name="siret"
            maxlength="30"
            [ngModel]="draft().legalIdentifier"
            (ngModelChange)="patch({ legalIdentifier: $event })"
        /></label>
        <label
          >N° FINESS<input
            class="field"
            name="finess"
            maxlength="9"
            [ngModel]="draft().finessNumber"
            (ngModelChange)="patch({ finessNumber: $event })"
        /></label>
        <label
          >Préfixe ordonnancier<input
            class="field"
            name="ord"
            maxlength="10"
            [ngModel]="draft().ordonnancierPrefix"
            (ngModelChange)="patch({ ordonnancierPrefix: $event })"
        /></label>

        <h2 class="mt-2 text-lg font-semibold md:col-span-2">Couleurs</h2>
        @for (color of colors; track color.key) {
          <label class="flex items-center gap-2">
            <input
              type="color"
              [name]="color.key"
              [ngModel]="draft()[color.key]"
              (ngModelChange)="setColor(color.key, $event)"
            />
            {{ color.label }} <code>{{ draft()[color.key] }}</code>
          </label>
        }
        <p class="text-sm md:col-span-2" [class.text-red-700]="contrast() < 4.5">
          Contraste du texte sur la couleur principale : {{ contrast().toFixed(1) }}:1
          {{ contrast() < 4.5 ? '(insuffisant, WCAG AA = 4.5)' : '(conforme WCAG AA)' }}
        </p>

        <h2 class="mt-2 text-lg font-semibold md:col-span-2">Ticket de caisse</h2>
        <label class="md:col-span-2"
          >En-tête<textarea
            class="field"
            name="header"
            maxlength="500"
            rows="2"
            [ngModel]="draft().receiptHeader"
            (ngModelChange)="patch({ receiptHeader: $event })"
          ></textarea>
        </label>
        <label class="md:col-span-2"
          >Pied de page<textarea
            class="field"
            name="footer"
            maxlength="500"
            rows="2"
            [ngModel]="draft().receiptFooter"
            (ngModelChange)="patch({ receiptFooter: $event })"
          ></textarea>
        </label>
        <label class="md:col-span-2"
          >Logo (PNG, JPEG ou WebP, 512 Ko max)
          <input type="file" accept="image/png,image/jpeg,image/webp" (change)="upload($event)"
        /></label>

        @if (status(); as text) {
          <p class="md:col-span-2" role="status">{{ text }}</p>
        }
        <div class="flex gap-2 md:col-span-2">
          <button type="submit" class="rounded bg-brand px-4 py-2 text-on-brand">
            Enregistrer
          </button>
          <button type="button" class="rounded border px-4 py-2" (click)="reset()">
            Annuler les modifications
          </button>
        </div>
      </form>
      <div>
        <p class="mb-2 text-sm text-slate-600">Aperçu du ticket</p>
        <app-receipt [data]="sample" class="block shadow" />
      </div>
    </div>
  `,
  styles: `
    .field {
      display: block;
      width: 100%;
      margin-top: 0.25rem;
      border: 1px solid #cbd5e1;
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
  `,
})
export class BrandingSettingsPage {
  private readonly service = inject(BrandingService);
  protected readonly draft = signal<Branding>({ ...this.service.branding() });
  protected readonly status = signal<string | null>(null);
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
      this.status.set('Identité enregistrée');
    } catch {
      this.status.set('Enregistrement impossible : vérifiez les champs');
    }
  }

  protected async upload(event: Event): Promise<void> {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    if (file.size > 512 * 1024) {
      this.status.set('Logo trop volumineux (512 Ko max)');
      return;
    }
    try {
      await this.service.uploadLogo(file);
      this.status.set('Logo mis à jour');
    } catch {
      this.status.set('Format de logo refusé');
    }
  }
}
