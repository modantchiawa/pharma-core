import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { PurchaseOrder } from '../../core/api/models';
import { BrandingService } from '../../core/branding/branding.service';
import { CurrencyService } from '../../core/ui/currency.service';
import { amountInWords } from '../../shared/amount-words';
import { MoneyPipe } from '../../shared/money.pipe';

/**
 * A4 purchase order ("bon de commande") with the pharmacy's identity, printed through the
 * browser from the print-only area of the order screen. Paper stays black on white.
 */
@Component({
  selector: 'app-purchase-order-print',
  imports: [MoneyPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="purchase-order mx-auto bg-white p-8 text-[12px] leading-snug text-black">
      <header class="flex items-start justify-between gap-6 border-b-2 border-black pb-4">
        <div class="flex items-start gap-4">
          @if (branding.logoUrl(); as logo) {
            <img [src]="logo" alt="" class="max-h-20 object-contain" />
          }
          <div>
            <p class="text-xl font-bold">{{ identity().displayName }}</p>
            @if (branding.fullAddress()) {
              <p>{{ branding.fullAddress() }}</p>
            }
            @if (identity().phone) {
              <p>Tél. {{ identity().phone }}</p>
            }
            @if (identity().email) {
              <p>{{ identity().email }}</p>
            }
            @if (identity().legalIdentifier) {
              <p>NIF {{ identity().legalIdentifier }}</p>
            }
          </div>
        </div>
        <div class="text-right">
          <p class="text-2xl font-bold">BON DE COMMANDE</p>
          <p>N° {{ order().number }}</p>
          <p>Du {{ order().sentAt ?? order().createdAt | date: 'dd/MM/yyyy' }}</p>
        </div>
      </header>

      <section class="mt-4 grid grid-cols-2 gap-6">
        <div class="rounded border border-black p-3">
          <p class="text-[10px] uppercase">Fournisseur</p>
          <p class="font-semibold">{{ order().supplierName }}</p>
          @if (order().supplier.contactName) {
            <p>À l'attention de {{ order().supplier.contactName }}</p>
          }
          @if (order().supplier.phone) {
            <p>Tél. {{ order().supplier.phone }}</p>
          }
          @if (order().supplier.email) {
            <p>{{ order().supplier.email }}</p>
          }
        </div>
        <div class="rounded border border-black p-3">
          <p class="text-[10px] uppercase">Livraison</p>
          <p>{{ identity().displayName }}</p>
          @if (branding.fullAddress()) {
            <p>{{ branding.fullAddress() }}</p>
          }
          @if (order().expectedAt) {
            <p class="mt-1 font-semibold">
              Livraison souhaitée le {{ order().expectedAt | date: 'dd/MM/yyyy' }}
            </p>
          }
        </div>
      </section>

      <table class="mt-4 w-full border-collapse">
        <thead>
          <tr class="border-b-2 border-black text-left">
            <th class="py-1">CIP</th>
            <th class="py-1">Désignation</th>
            <th class="py-1 text-right">Qté</th>
            <th class="py-1 text-right">P.U. HT</th>
            <th class="py-1 text-right">Total HT</th>
          </tr>
        </thead>
        <tbody>
          @for (line of order().lines; track line.productId) {
            <tr class="border-b border-black/30">
              <td class="py-1 font-mono">{{ line.cip13 }}</td>
              <td class="py-1">{{ line.designation }}</td>
              <td class="py-1 text-right">{{ line.quantityOrdered }}</td>
              <td class="py-1 text-right">{{ line.unitCostCents | money }}</td>
              <td class="py-1 text-right">
                {{ line.unitCostCents * line.quantityOrdered | money }}
              </td>
            </tr>
          }
        </tbody>
      </table>

      <section class="mt-4 flex justify-end">
        <table class="w-72">
          <tbody>
            <tr>
              <td class="py-0.5">Références</td>
              <td class="py-0.5 text-right">{{ order().lines.length }}</td>
            </tr>
            <tr>
              <td class="py-0.5">Unités</td>
              <td class="py-0.5 text-right">{{ order().orderedUnits }}</td>
            </tr>
            <tr class="border-t-2 border-black text-base font-bold">
              <td class="py-1">Total HT</td>
              <td class="py-1 text-right">{{ order().totalCents | money }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <p class="mt-4 italic">
        Arrêté le présent bon de commande à la somme de {{ amountWords() }} hors taxes.
      </p>
      @if (order().notes) {
        <p class="mt-2">Observations : {{ order().notes }}</p>
      }

      <section class="mt-10 grid grid-cols-2 gap-6">
        <div>
          <p class="text-[10px] uppercase">Le pharmacien</p>
          <div class="mt-1 h-20 border-b border-black"></div>
        </div>
        <div>
          <p class="text-[10px] uppercase">Cachet de la pharmacie</p>
          <div class="mt-1 h-20 border-b border-black"></div>
        </div>
      </section>
    </article>
  `,
  styles: `
    .purchase-order {
      width: 190mm;
      min-height: 250mm;
    }
    @media print {
      .purchase-order {
        width: auto;
      }
    }
  `,
})
export class PurchaseOrderPrint {
  protected readonly branding = inject(BrandingService);
  private readonly currency = inject(CurrencyService);
  readonly order = input.required<PurchaseOrder>();
  protected readonly identity = computed(() => this.branding.branding());
  protected readonly amountWords = computed(() =>
    amountInWords(this.order().totalCents, this.currency.current()),
  );
}
