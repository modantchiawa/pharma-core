import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { GoodsReceiptRequest, GoodsReceiptResult, PurchaseOrder } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { ToastService } from '../../core/ui/toast.service';
import { parseGs1 } from '../../shared/gs1';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import {
  applyGs1,
  errorDetail,
  isoToday,
  ReceiptLineDraft,
  receiptDraft,
  receiptLineProblem,
} from './purchase-orders';

/**
 * Delivery against an order: each awaited line is pre-filled with what remains and its order
 * price; the pharmacist types (or scans) lot and expiry, adjusts what actually came, and the
 * lots enter the stock with the purchase entry in one call. Partial deliveries are fine.
 */
@Component({
  selector: 'app-order-receive',
  imports: [FormsModule, MoneyPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="space-y-4" (ngSubmit)="submit()" aria-label="Réception de la livraison">
      <div class="grid gap-3 sm:grid-cols-2">
        <label class="block">
          <span class="label">N° de facture / bon de livraison</span>
          <input
            class="input"
            name="invoice"
            maxlength="40"
            [(ngModel)]="invoiceNumber"
            placeholder="Ex. F-2026-1234"
          />
        </label>
        <label class="block">
          <span class="label">Date de facture</span>
          <input class="input" type="date" name="invoiceDate" [(ngModel)]="invoiceDate" />
        </label>
      </div>

      <label class="block">
        <span class="label">Scanner une boîte (DataMatrix GS1)</span>
        <span class="input-with-icon">
          <app-icon name="barcode" [size]="16" />
          <input
            class="input"
            name="scan"
            autocomplete="off"
            [(ngModel)]="scan"
            (keydown.enter)="applyScan($event)"
            placeholder="Le lot et la péremption se remplissent seuls"
          />
        </span>
      </label>

      <ul class="space-y-3" aria-label="Lignes livrées">
        @for (line of lines(); track line.productId; let i = $index) {
          <li
            class="rounded-[var(--radius)] border border-line bg-surface-2 p-3"
            [class.opacity-60]="!(line.quantity > 0)"
            [attr.data-testid]="'receive-line-' + line.cip13"
          >
            <div class="flex flex-wrap items-start justify-between gap-2">
              <div class="min-w-0">
                <p class="font-medium">{{ line.designation }}</p>
                <p class="text-xs text-subtle tabular-nums">{{ line.cip13 }}</p>
              </div>
              <span class="badge">Reste {{ line.remaining }}</span>
            </div>
            <div class="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <label class="block">
                <span class="label">Quantité reçue</span>
                <input
                  class="input num"
                  type="number"
                  min="0"
                  [name]="'qty' + i"
                  [(ngModel)]="line.quantity"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="block">
                <span class="label">N° de lot</span>
                <input
                  class="input"
                  maxlength="40"
                  [name]="'lot' + i"
                  [(ngModel)]="line.lotNumber"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="block">
                <span class="label">Péremption</span>
                <input
                  class="input"
                  type="date"
                  [min]="today"
                  [name]="'exp' + i"
                  [(ngModel)]="line.expiryDate"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="block">
                <span class="label">Prix d'achat HT</span>
                <input
                  class="input num"
                  inputmode="decimal"
                  [name]="'cost' + i"
                  [(ngModel)]="line.unitCost"
                  (ngModelChange)="touch()"
                />
              </label>
              <label class="block">
                <span class="label">TVA % <span class="text-subtle">(facultatif)</span></span>
                <input
                  class="input num"
                  inputmode="decimal"
                  [name]="'vat' + i"
                  [(ngModel)]="line.vatRate"
                  (ngModelChange)="touch()"
                  placeholder="0"
                />
              </label>
              <label class="block">
                <span class="label"
                  >Prix de vente <span class="text-subtle">(facultatif)</span></span
                >
                <input
                  class="input num"
                  inputmode="decimal"
                  [name]="'price' + i"
                  [(ngModel)]="line.sellingPrice"
                  placeholder="Inchangé"
                />
              </label>
            </div>
            @if (problem(line); as text) {
              <p class="mt-2 flex items-center gap-1.5 text-xs text-danger">
                <app-icon name="alert" [size]="13" />{{ text }}
              </p>
            } @else if (line.quantity > line.remaining) {
              <p class="mt-2 text-xs text-warning">
                Plus que la quantité attendue ({{ line.remaining }})
              </p>
            }
          </li>
        }
      </ul>

      <div
        class="sticky -bottom-4 -mx-4 -mb-4 flex flex-wrap items-center justify-between gap-3 border-t border-line bg-surface px-4 pb-7 pt-3 sm:-mx-5 sm:px-5"
      >
        <p class="text-sm text-muted">{{ units() }} unité(s) · {{ totalCents() | money }} HT</p>
        <div class="flex gap-2">
          <button type="button" class="btn btn-ghost" (click)="cancelled.emit()">Annuler</button>
          <button type="submit" class="btn btn-primary" [disabled]="!valid() || busy()">
            <app-icon name="receive" [size]="16" />
            Enregistrer la réception
          </button>
        </div>
      </div>
    </form>
  `,
})
export class OrderReceive implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/procurement`;
  private readonly currency = inject(CurrencyService);
  private readonly toast = inject(ToastService);

  readonly order = input.required<PurchaseOrder>();
  readonly received = output<GoodsReceiptResult>();
  readonly cancelled = output<void>();

  protected readonly today = isoToday();
  protected readonly lines = signal<ReceiptLineDraft[]>([]);
  protected readonly busy = signal(false);
  /** Bumped on every edit: the lines are mutated in place by the form. */
  private readonly revision = signal(0);
  protected invoiceNumber = '';
  protected invoiceDate = this.today;
  protected scan = '';

  protected readonly units = computed(() => {
    this.revision();
    return this.lines().reduce((sum, l) => sum + (l.quantity > 0 ? Number(l.quantity) : 0), 0);
  });
  protected readonly totalCents = computed(() => {
    this.revision();
    return this.lines().reduce(
      (sum, l) => sum + (l.quantity > 0 ? Number(l.quantity) * this.currency.parse(l.unitCost) : 0),
      0,
    );
  });
  protected readonly valid = computed(() => {
    this.revision();
    const lines = this.lines();
    return (
      lines.some((l) => l.quantity > 0) &&
      lines.every((l) => receiptLineProblem(l, this.today) === null)
    );
  });

  ngOnInit(): void {
    this.lines.set(receiptDraft(this.order(), (minor) => this.currency.toUnits(minor)));
  }

  protected problem(line: ReceiptLineDraft): string | null {
    return receiptLineProblem(line, this.today);
  }

  protected touch(): void {
    this.revision.update((n) => n + 1);
  }

  protected applyScan(event: Event): void {
    event.preventDefault();
    const raw = this.scan.trim();
    this.scan = '';
    if (!raw) {
      return;
    }
    const code = parseGs1(raw);
    if (!code) {
      this.toast.error('Code non reconnu : scannez le DataMatrix de la boîte');
      return;
    }
    const index = applyGs1(this.lines(), code);
    if (index < 0) {
      this.toast.error('Ce produit ne figure pas sur la commande');
      return;
    }
    this.touch();
    this.toast.info(`${this.lines()[index].designation} : lot ${code.lot ?? '—'} renseigné`);
  }

  protected async submit(): Promise<void> {
    if (!this.valid() || this.busy()) {
      return;
    }
    const order = this.order();
    const request: GoodsReceiptRequest = {
      supplierId: order.supplierId,
      purchaseOrderId: order.id,
      supplierInvoiceNumber: this.invoiceNumber.trim() || null,
      invoiceDate: this.invoiceDate || null,
      notes: null,
      lines: this.lines()
        .filter((l) => l.quantity > 0)
        .map((l) => ({
          productId: l.productId,
          lotNumber: l.lotNumber.trim(),
          expiryDate: l.expiryDate,
          quantity: Number(l.quantity),
          unitCostCents: this.currency.parse(l.unitCost),
          vatRate: l.vatRate.trim() === '' ? null : Number(l.vatRate.replace(',', '.')),
          sellingPriceCents:
            l.sellingPrice.trim() === '' ? null : this.currency.parse(l.sellingPrice),
        })),
    };
    this.busy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<GoodsReceiptResult>(`${this.api}/receipts`, request),
      );
      this.toast.success(
        result.orderStatus === 'RECEIVED'
          ? `Commande ${order.number} reçue en totalité`
          : `Réception enregistrée : ${this.units()} unité(s) en stock`,
      );
      this.received.emit(result);
    } catch (error) {
      this.toast.error(errorDetail(error, 'Réception refusée'));
    } finally {
      this.busy.set(false);
    }
  }
}
