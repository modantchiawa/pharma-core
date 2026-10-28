import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { PaperFormat } from '../../core/api/models';
import { BrandingService } from '../../core/branding/branding.service';
import { CurrencyService } from '../../core/ui/currency.service';
import { amountInWords } from '../../shared/amount-words';
import { Posology, posologySummary } from '../../shared/posology';
import { MoneyPipe } from '../../shared/money.pipe';
import { CartLine, CartPayment } from './cart.store';

export interface ReceiptCustomer {
  displayName: string;
  phone: string | null;
  address: string | null;
  /** Insurer and policy number when part of the sale is covered. */
  policy: string | null;
}

export interface ReceiptData {
  ticketNumber: string;
  terminalId: string;
  occurredAt: Date;
  cashier: string;
  customer?: ReceiptCustomer | null;
  lines: CartLine[];
  payments: CartPayment[];
  grossCents: number;
  discountCents: number;
  netCents: number;
  changeDueCents: number;
  vat: { rate: number; totalTtc: number; vat: number }[];
  offline: boolean;
  cancelled?: boolean;
  certificationCode?: string | null;
  certificationQr?: string | null;
}

export const PAYMENT_LABELS: Record<string, string> = {
  CASH: 'Espèces',
  CARD: 'Carte bancaire',
  CHECK: 'Chèque',
  THIRD_PARTY_PAYER: 'Tiers payant',
  VOUCHER: 'Avoir',
  MOBILE_MONEY: 'Mobile money',
  CREDIT: 'Crédit client',
};

/**
 * Printable sale document in three formats: a 58 mm ticket for the small printers sold with
 * payment terminals, the usual 80 mm thermal ticket, and an A4 invoice for insurers and
 * companies. All three carry the pharmacy's identity and legal mentions.
 */
@Component({
  selector: 'app-receipt',
  imports: [MoneyPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (format() === 'A4') {
      <article class="invoice mx-auto bg-white p-8 text-[12px] leading-snug text-black">
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
            <p class="text-2xl font-bold">FACTURE</p>
            <p>N° {{ data().ticketNumber }}</p>
            <p>{{ data().occurredAt | date: 'dd/MM/yyyy HH:mm' }}</p>
            <p>Caisse {{ data().terminalId }} · {{ data().cashier }}</p>
          </div>
        </header>

        @if (data().cancelled) {
          <p class="mt-4 border-2 border-black p-2 text-center text-lg font-bold">VENTE ANNULÉE</p>
        }

        <section class="mt-4 grid grid-cols-2 gap-6">
          <div class="rounded border border-slate-400 p-3">
            <p class="text-[10px] uppercase text-slate-600">Client</p>
            @if (data().customer; as customer) {
              <p class="font-semibold">{{ customer.displayName }}</p>
              @if (customer.address) {
                <p>{{ customer.address }}</p>
              }
              @if (customer.phone) {
                <p>Tél. {{ customer.phone }}</p>
              }
              @if (customer.policy) {
                <p>Police d'assurance : {{ customer.policy }}</p>
              }
            } @else {
              <p>Client de passage</p>
            }
          </div>
          <div class="rounded border border-slate-400 p-3">
            <p class="text-[10px] uppercase text-slate-600">Règlement</p>
            @for (payment of data().payments; track $index) {
              <p class="flex justify-between">
                <span>{{ label(payment) }}</span
                ><span>{{ payment.amountCents | money }}</span>
              </p>
            }
            @if (data().changeDueCents > 0) {
              <p class="flex justify-between">
                <span>Rendu</span><span>{{ data().changeDueCents | money }}</span>
              </p>
            }
          </div>
        </section>

        <table class="mt-4 w-full border-collapse">
          <thead>
            <tr class="border-b-2 border-black text-left">
              <th class="py-1">Désignation</th>
              <th class="py-1 text-right">Qté</th>
              <th class="py-1 text-right">P.U. TTC</th>
              <th class="py-1 text-right">Remise</th>
              <th class="py-1 text-right">TVA</th>
              <th class="py-1 text-right">Total TTC</th>
            </tr>
          </thead>
          <tbody>
            @for (line of data().lines; track line.productId) {
              <tr class="border-b border-slate-300">
                <td class="py-1">
                  {{ line.designation }}
                  @if (line.posology) {
                    <span class="block text-[10px] text-slate-700">
                      Posologie : {{ summaryOf(line.posology) }}
                    </span>
                  }
                </td>
                <td class="py-1 text-right">{{ line.quantity }}</td>
                <td class="py-1 text-right">{{ line.unitPriceCents | money }}</td>
                <td class="py-1 text-right">
                  {{ line.discountCents > 0 ? '-' + (line.discountCents | money) : '' }}
                </td>
                <td class="py-1 text-right">{{ line.vatRate }} %</td>
                <td class="py-1 text-right">
                  {{ line.unitPriceCents * line.quantity - line.discountCents | money }}
                </td>
              </tr>
            }
          </tbody>
        </table>

        <section class="mt-4 flex justify-end">
          <table class="w-72">
            <tbody>
              <tr>
                <td class="py-0.5">Total HT</td>
                <td class="py-0.5 text-right">{{ data().netCents - totalVat() | money }}</td>
              </tr>
              @for (row of data().vat; track row.rate) {
                <tr>
                  <td class="py-0.5">TVA {{ row.rate }} %</td>
                  <td class="py-0.5 text-right">{{ row.vat | money }}</td>
                </tr>
              }
              @if (data().discountCents > 0) {
                <tr>
                  <td class="py-0.5">Remises accordées</td>
                  <td class="py-0.5 text-right">-{{ data().discountCents | money }}</td>
                </tr>
              }
              <tr class="border-t-2 border-black text-base font-bold">
                <td class="py-1">Total TTC</td>
                <td class="py-1 text-right">{{ data().netCents | money }}</td>
              </tr>
            </tbody>
          </table>
        </section>

        <p class="mt-4 italic">Arrêtée la présente facture à la somme de {{ amountWords() }}.</p>
        @if (data().certificationCode) {
          <p class="mt-2">Facture certifiée : {{ data().certificationCode }}</p>
        }
        @if (identity().receiptFooter) {
          <footer
            class="mt-8 whitespace-pre-line border-t border-slate-400 pt-2 text-center text-[11px]"
          >
            {{ identity().receiptFooter }}
          </footer>
        }
      </article>
    } @else {
      <article
        class="receipt mx-auto bg-white font-mono leading-tight text-black"
        [class.w-[58mm]]="format() === 'MM58'"
        [class.w-[80mm]]="format() === 'MM80'"
        [class.p-1]="format() === 'MM58'"
        [class.p-3]="format() === 'MM80'"
        [class.text-[9px]]="format() === 'MM58'"
        [class.text-[11px]]="format() === 'MM80'"
      >
        <header class="text-center">
          @if (format() === 'MM80' && branding.logoUrl(); as logo) {
            <img [src]="logo" alt="" class="mx-auto mb-1 max-h-14 object-contain" />
          }
          <p class="font-bold" [class.text-sm]="format() === 'MM80'">
            {{ identity().displayName }}
          </p>
          @if (branding.fullAddress()) {
            <p>{{ branding.fullAddress() }}</p>
          }
          @if (identity().phone) {
            <p>Tél. {{ identity().phone }}</p>
          }
          @if (identity().legalIdentifier) {
            <p>NIF {{ identity().legalIdentifier }}</p>
          }
          @if (identity().receiptHeader) {
            <p class="mt-1 whitespace-pre-line">{{ identity().receiptHeader }}</p>
          }
        </header>

        @if (data().cancelled) {
          <p class="my-1 text-center font-bold">*** VENTE ANNULÉE ***</p>
        }
        <p class="my-2 border-y border-dashed border-black py-1">
          Ticket {{ data().ticketNumber }} · {{ data().occurredAt | date: 'dd/MM/yyyy HH:mm'
          }}<br />
          Caisse {{ data().terminalId }} · {{ data().cashier }}
          @if (data().customer; as customer) {
            <br />Client {{ customer.displayName }}
            @if (customer.policy) {
              <br />Police {{ customer.policy }}
            }
          }
        </p>

        <table class="w-full">
          <tbody>
            @for (line of data().lines; track line.productId) {
              <tr>
                <td colspan="2" class="break-words">{{ shorten(line.designation) }}</td>
              </tr>
              <tr>
                <td class="whitespace-nowrap">
                  {{ line.quantity }} × {{ line.unitPriceCents | money }}
                </td>
                <td class="whitespace-nowrap text-right">
                  {{ line.unitPriceCents * line.quantity - line.discountCents | money }}
                </td>
              </tr>
              @if (line.discountCents > 0) {
                <tr>
                  <td class="pl-2">Remise</td>
                  <td class="text-right">-{{ line.discountCents | money }}</td>
                </tr>
              }
              @if (line.posology) {
                <tr>
                  <td colspan="2" class="pl-2 font-bold break-words">
                    &gt; {{ summaryOf(line.posology) }}
                  </td>
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
          <p class="flex justify-between font-bold" [class.text-sm]="format() === 'MM80'">
            <span>TOTAL TTC</span><span>{{ data().netCents | money }}</span>
          </p>
          @for (payment of data().payments; track $index) {
            <p class="flex justify-between">
              <span>{{ label(payment) }}</span
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

        @if (data().certificationCode) {
          <p class="mt-2 text-center">Facture certifiée {{ data().certificationCode }}</p>
        }
        @if (data().offline) {
          <p class="mt-2 text-center italic">
            Vente enregistrée hors-ligne · synchronisation automatique
          </p>
        }
        @if (identity().receiptFooter) {
          <p class="mt-2 whitespace-pre-line text-center">{{ identity().receiptFooter }}</p>
        }
      </article>
    }
  `,
  styles: `
    .invoice {
      width: 190mm;
      min-height: 250mm;
    }
    @media print {
      .invoice {
        width: auto;
      }
    }
  `,
})
export class Receipt {
  protected readonly branding = inject(BrandingService);
  private readonly currency = inject(CurrencyService);
  readonly data = input.required<ReceiptData>();
  readonly format = input<PaperFormat>('MM80');
  protected readonly identity = computed(() => this.branding.branding());
  protected readonly totalVat = computed(() =>
    this.data().vat.reduce((sum, row) => sum + row.vat, 0),
  );
  protected readonly amountWords = computed(() =>
    amountInWords(this.data().netCents, this.currency.current()),
  );

  protected label(payment: CartPayment): string {
    const base = PAYMENT_LABELS[payment.method] ?? payment.method;
    return payment.provider ? `${base} ${payment.provider.replace(/_/g, ' ').toLowerCase()}` : base;
  }

  /** A 58 mm ticket holds about 30 characters: long names are cut on a word boundary. */
  protected summaryOf(posology: Posology): string {
    return posologySummary(posology);
  }

  protected shorten(designation: string): string {
    if (this.format() !== 'MM58' || designation.length <= 30) {
      return designation;
    }
    const cut = designation.slice(0, 30);
    const space = cut.lastIndexOf(' ');
    return (space > 18 ? cut.slice(0, space) : cut) + '…';
  }
}
