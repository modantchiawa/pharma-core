import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { BrandingService } from '../../core/branding/branding.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { CartLine, CartPayment } from './cart.store';

export interface ReceiptData {
  ticketNumber: string;
  terminalId: string;
  occurredAt: Date;
  cashier: string;
  lines: CartLine[];
  payments: CartPayment[];
  grossCents: number;
  discountCents: number;
  netCents: number;
  changeDueCents: number;
  vat: { rate: number; totalTtc: number; vat: number }[];
  offline: boolean;
}

const PAYMENT_LABELS: Record<string, string> = {
  CASH: 'Espèces',
  CARD: 'Carte bancaire',
  CHECK: 'Chèque',
  THIRD_PARTY_PAYER: 'Tiers payant',
  VOUCHER: 'Avoir',
};

/** 80 mm thermal ticket carrying the pharmacy's own identity and legal mentions. */
@Component({
  selector: 'app-receipt',
  imports: [MoneyPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article
      class="receipt mx-auto w-[80mm] bg-white p-3 font-mono text-[11px] leading-tight text-black"
    >
      <header class="text-center">
        @if (branding.logoUrl(); as logo) {
          <img [src]="logo" alt="" class="mx-auto mb-1 max-h-14 object-contain" />
        }
        <p class="text-sm font-bold">{{ identity().displayName }}</p>
        @if (branding.fullAddress()) {
          <p>{{ branding.fullAddress() }}</p>
        }
        @if (identity().phone) {
          <p>Tél. {{ identity().phone }}</p>
        }
        @if (identity().legalIdentifier) {
          <p>SIRET {{ identity().legalIdentifier }}</p>
        }
        @if (identity().receiptHeader) {
          <p class="mt-1 whitespace-pre-line">{{ identity().receiptHeader }}</p>
        }
      </header>

      <p class="my-2 border-y border-dashed border-black py-1">
        Ticket {{ data().ticketNumber }} · {{ data().occurredAt | date: 'dd/MM/yyyy HH:mm' }}<br />
        Caisse {{ data().terminalId }} · {{ data().cashier }}
      </p>

      <table class="w-full">
        <tbody>
          @for (line of data().lines; track line.productId) {
            <tr>
              <td colspan="2">{{ line.designation }}</td>
            </tr>
            <tr>
              <td>{{ line.quantity }} × {{ line.unitPriceCents | money }}</td>
              <td class="text-right">
                {{ line.unitPriceCents * line.quantity - line.discountCents | money }}
              </td>
            </tr>
            @if (line.discountCents > 0) {
              <tr>
                <td class="pl-2">Remise</td>
                <td class="text-right">-{{ line.discountCents | money }}</td>
              </tr>
            }
          }
        </tbody>
      </table>

      <div class="mt-2 border-t border-dashed border-black pt-1">
        @if (data().discountCents > 0) {
          <p class="flex justify-between">
            <span>Total remises</span><span>-{{ data().discountCents | money }}</span>
          </p>
        }
        <p class="flex justify-between text-sm font-bold">
          <span>TOTAL TTC</span><span>{{ data().netCents | money }}</span>
        </p>
        @for (payment of data().payments; track $index) {
          <p class="flex justify-between">
            <span>{{ label(payment.method) }}</span
            ><span>{{ payment.amountCents | money }}</span>
          </p>
        }
        @if (data().changeDueCents > 0) {
          <p class="flex justify-between">
            <span>Rendu</span><span>{{ data().changeDueCents | money }}</span>
          </p>
        }
      </div>

      <table class="mt-2 w-full">
        <thead>
          <tr>
            <th class="text-left">TVA</th>
            <th class="text-right">TTC</th>
            <th class="text-right">dont TVA</th>
          </tr>
        </thead>
        <tbody>
          @for (row of data().vat; track row.rate) {
            <tr>
              <td>{{ row.rate }} %</td>
              <td class="text-right">{{ row.totalTtc | money }}</td>
              <td class="text-right">{{ row.vat | money }}</td>
            </tr>
          }
        </tbody>
      </table>

      @if (data().offline) {
        <p class="mt-2 text-center italic">
          Vente enregistrée hors-ligne · synchronisation automatique
        </p>
      }
      @if (identity().receiptFooter) {
        <p class="mt-2 whitespace-pre-line text-center">{{ identity().receiptFooter }}</p>
      }
    </article>
  `,
})
export class Receipt {
  protected readonly branding = inject(BrandingService);
  readonly data = input.required<ReceiptData>();
  protected readonly identity = computed(() => this.branding.branding());

  protected label(method: string): string {
    return PAYMENT_LABELS[method] ?? method;
  }
}
