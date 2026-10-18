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
import { PaymentMethod } from '../../core/api/models';
import { PermissionService } from '../../core/auth/permission.service';
import { CatalogSyncService } from '../../core/offline/catalog-sync.service';
import { ConnectivityService } from '../../core/offline/connectivity.service';
import { CachedProduct } from '../../core/offline/pos-database';
import { PosDatabaseService } from '../../core/offline/pos-database.service';
import { SaleOutboxService } from '../../core/offline/sale-outbox.service';
import { TerminalService } from '../../core/offline/terminal.service';
import { uuidv7 } from '../../core/util/uuid';
import { MoneyPipe } from '../../shared/money.pipe';
import { CartStore, discountPercent } from './cart.store';
import { Receipt, ReceiptData } from './receipt';

/**
 * Counter sale screen designed for keyboard and barcode scanner: the scanner types the EAN-13
 * followed by Enter. Shortcuts: F2 search, F4 cash payment of the remainder, F9 validate, Esc clear.
 */
@Component({
  selector: 'app-pos-page',
  imports: [FormsModule, MoneyPipe, Receipt],
  providers: [CartStore],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'onShortcut($event)' },
  template: `
    <div class="grid h-full gap-4 p-4 lg:grid-cols-[1fr_380px] print:hidden">
      <section class="flex min-h-0 flex-col gap-3">
        <div class="relative">
          <input
            #search
            type="search"
            autocomplete="off"
            autofocus
            class="w-full rounded-lg border-2 border-brand px-4 py-3 text-lg focus:outline-none focus:ring-4 focus:ring-brand/30"
            placeholder="Scanner un code-barres ou rechercher (F2)"
            [(ngModel)]="term"
            (ngModelChange)="lookup($event)"
            (keydown.enter)="scan()"
            (keydown.arrowdown)="move(1)"
            (keydown.arrowup)="move(-1)"
            aria-label="Recherche produit"
          />
          @if (results().length > 0) {
            <ul
              class="absolute z-10 mt-1 max-h-80 w-full overflow-auto rounded-lg border bg-white shadow-lg"
              role="listbox"
            >
              @for (product of results(); track product.productId; let i = $index) {
                <li
                  role="option"
                  [attr.aria-selected]="i === highlighted()"
                  class="flex cursor-pointer justify-between px-4 py-2"
                  [class.bg-brand]="i === highlighted()"
                  [class.text-on-brand]="i === highlighted()"
                  (mousedown)="pick(product)"
                >
                  <span
                    >{{ product.designation }}
                    <small class="opacity-70">{{ product.cip13 }}</small></span
                  >
                  <span
                    >{{ product.unitPriceCents | money }} · stock
                    {{ product.availableQuantity }}</span
                  >
                </li>
              }
            </ul>
          }
        </div>
        @if (message(); as text) {
          <p class="rounded bg-amber-100 px-3 py-2 text-amber-900" role="alert">{{ text }}</p>
        }

        <div class="min-h-0 flex-1 overflow-auto rounded-lg border bg-white">
          <table class="w-full text-left">
            <thead class="sticky top-0 bg-slate-100 text-sm">
              <tr>
                <th class="p-2">Produit</th>
                <th class="p-2 w-28">Qté</th>
                <th class="p-2 w-24">Remise %</th>
                <th class="p-2 text-right">Montant</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (line of cart.lines(); track line.productId) {
                <tr class="border-t">
                  <td class="p-2">
                    {{ line.designation }}
                    @if (line.listing !== 'NONE') {
                      <span class="ml-1 rounded bg-red-100 px-1 text-xs text-red-800">{{
                        line.listing
                      }}</span>
                    }
                    <div class="text-xs text-slate-500">
                      {{ line.unitPriceCents | money }} · TVA {{ line.vatRate }} %
                    </div>
                  </td>
                  <td class="p-2">
                    <input
                      type="number"
                      min="0"
                      class="w-20 rounded border px-2 py-1"
                      [ngModel]="line.quantity"
                      (ngModelChange)="cart.setQuantity(line.productId, $event)"
                      [attr.aria-label]="'Quantité ' + line.designation"
                    />
                  </td>
                  <td class="p-2">
                    <input
                      type="number"
                      min="0"
                      max="100"
                      step="0.5"
                      class="w-20 rounded border px-2 py-1"
                      [ngModel]="percent(line)"
                      (ngModelChange)="cart.setDiscountPercent(line.productId, $event)"
                      [attr.aria-label]="'Remise ' + line.designation"
                    />
                  </td>
                  <td class="p-2 text-right">
                    {{ line.unitPriceCents * line.quantity - line.discountCents | money }}
                  </td>
                  <td class="p-2">
                    <button
                      type="button"
                      class="text-red-700"
                      (click)="cart.remove(line.productId)"
                      aria-label="Retirer"
                    >
                      ✕
                    </button>
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="5" class="p-8 text-center text-slate-500">
                    Panier vide : scannez un produit
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <aside class="flex flex-col gap-3 rounded-lg border bg-white p-4">
        <div class="flex items-baseline justify-between">
          <span class="text-slate-600">{{ cart.itemCount() }} article(s)</span>
          <span class="text-3xl font-bold text-brand">{{ cart.netCents() | money }}</span>
        </div>
        @if (cart.discountCents() > 0) {
          <p class="text-right text-sm text-slate-600">
            dont remises {{ cart.discountCents() | money }}
          </p>
        }

        @if (cart.requiresPrescription()) {
          <fieldset class="rounded border p-2">
            <legend class="px-1 text-sm font-semibold">Ordonnance</legend>
            <input
              class="mb-1 w-full rounded border px-2 py-1"
              placeholder="Prescripteur"
              [ngModel]="cart.prescription()?.prescriberName"
              (ngModelChange)="updatePrescription({ prescriberName: $event })"
              aria-label="Prescripteur"
            />
            <input
              class="mb-1 w-full rounded border px-2 py-1"
              placeholder="RPPS (11 chiffres)"
              [ngModel]="cart.prescription()?.prescriberRpps"
              (ngModelChange)="updatePrescription({ prescriberRpps: $event })"
              aria-label="RPPS"
            />
            <input
              class="w-full rounded border px-2 py-1"
              placeholder="Référence patient (pseudonyme)"
              [ngModel]="cart.prescription()?.patientReference"
              (ngModelChange)="updatePrescription({ patientReference: $event })"
              aria-label="Référence patient"
            />
          </fieldset>
        }

        <div class="grid grid-cols-2 gap-2">
          @for (method of methods; track method.code) {
            <button
              type="button"
              class="rounded border-2 border-brand px-2 py-2 font-medium text-brand hover:bg-brand hover:text-on-brand"
              (click)="pay(method.code)"
            >
              {{ method.label }}
            </button>
          }
        </div>
        <label class="text-sm"
          >Montant
          <input
            type="number"
            min="0"
            step="0.01"
            class="mt-1 w-full rounded border px-2 py-1"
            [(ngModel)]="amount"
            placeholder="Reste {{ cart.remainingCents() / 100 }}"
          />
        </label>

        <ul class="text-sm">
          @for (payment of cart.payments(); track $index) {
            <li class="flex justify-between">
              <span>{{ labelOf(payment.method) }}</span>
              <span
                >{{ payment.amountCents | money }}
                <button
                  type="button"
                  class="ml-1 text-red-700"
                  (click)="cart.removePayment($index)"
                  aria-label="Supprimer le règlement"
                >
                  ✕
                </button></span
              >
            </li>
          }
        </ul>
        <p class="flex justify-between font-semibold">
          <span>Reste à payer</span><span>{{ cart.remainingCents() | money }}</span>
        </p>
        @if (cart.changeDueCents() > 0) {
          <p class="flex justify-between text-lg font-bold text-accent">
            <span>Rendu monnaie</span><span>{{ cart.changeDueCents() | money }}</span>
          </p>
        }

        @for (issue of cart.blockingIssues(); track issue) {
          <p class="text-sm text-red-700">• {{ issue }}</p>
        }
        @for (warning of cart.stockWarnings(); track warning) {
          <p class="text-sm text-amber-700">⚠ {{ warning }}</p>
        }

        <button
          type="button"
          class="mt-auto rounded-lg bg-brand py-3 text-lg font-bold text-on-brand disabled:opacity-40"
          [disabled]="!cart.canCheckout() || busy()"
          (click)="checkout()"
        >
          Valider la vente (F9)
        </button>
        <p class="text-center text-xs" [class.text-amber-700]="!connectivity.online()">
          {{
            connectivity.online()
              ? 'En ligne'
              : 'Hors-ligne : la vente sera synchronisée automatiquement'
          }}
          · {{ outbox.pendingCount() }} en attente
        </p>
      </aside>
    </div>

    @if (receipt(); as data) {
      <div class="hidden print:block"><app-receipt [data]="data" /></div>
    }
  `,
})
export class PosPage {
  protected readonly cart = inject(CartStore);
  protected readonly connectivity = inject(ConnectivityService);
  protected readonly outbox = inject(SaleOutboxService);
  private readonly catalog = inject(CatalogSyncService);
  private readonly terminal = inject(TerminalService);
  private readonly permissions = inject(PermissionService);
  private readonly databases = inject(PosDatabaseService);
  private readonly searchInput = viewChild.required<ElementRef<HTMLInputElement>>('search');

  protected term = '';
  protected amount: number | null = null;
  protected readonly results = signal<CachedProduct[]>([]);
  protected readonly highlighted = signal(0);
  protected readonly message = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly receipt = signal<ReceiptData | null>(null);
  protected readonly methods: { code: PaymentMethod; label: string }[] = [
    { code: 'CASH', label: 'Espèces' },
    { code: 'CARD', label: 'Carte' },
    { code: 'THIRD_PARTY_PAYER', label: 'Tiers payant' },
    { code: 'CHECK', label: 'Chèque' },
  ];
  private readonly cashierName = computed(() => this.permissions.profile()?.displayName ?? '');

  protected async lookup(term: string): Promise<void> {
    this.highlighted.set(0);
    this.results.set(
      term.trim().length >= 2 && !/^\d+$/.test(term) ? await this.catalog.search(term) : [],
    );
  }

  protected async scan(): Promise<void> {
    const term = this.term.trim();
    if (!term) {
      return;
    }
    const candidates = this.results().length > 0 ? this.results() : await this.catalog.search(term);
    const product = candidates[this.highlighted()] ?? candidates[0];
    if (product) {
      this.pick(product);
    } else {
      this.message.set(`Aucun produit pour « ${term} »`);
    }
  }

  protected pick(product: CachedProduct): void {
    try {
      this.cart.add(product);
      this.message.set(null);
    } catch (error) {
      this.message.set((error as Error).message);
    }
    this.term = '';
    this.results.set([]);
    this.searchInput().nativeElement.focus();
  }

  protected move(delta: number): void {
    const count = this.results().length;
    if (count > 0) {
      this.highlighted.update((index) => (index + delta + count) % count);
    }
  }

  protected percent(line: {
    unitPriceCents: number;
    quantity: number;
    discountCents: number;
  }): number {
    return discountPercent(line);
  }

  protected updatePrescription(
    change: Partial<{ prescriberName: string; prescriberRpps: string; patientReference: string }>,
  ): void {
    this.cart.prescription.update((current) => ({ prescriberName: '', ...current, ...change }));
  }

  protected pay(method: PaymentMethod): void {
    const cents =
      this.amount !== null && this.amount > 0
        ? Math.round(this.amount * 100)
        : this.cart.remainingCents();
    this.cart.addPayment({
      method,
      amountCents: cents,
      thirdPartyCode: method === 'THIRD_PARTY_PAYER' ? 'AMO' : undefined,
    });
    this.amount = null;
  }

  protected labelOf(method: PaymentMethod): string {
    return this.methods.find((m) => m.code === method)?.label ?? method;
  }

  protected onShortcut(event: KeyboardEvent): void {
    switch (event.key) {
      case 'F2':
        event.preventDefault();
        this.searchInput().nativeElement.focus();
        break;
      case 'F4':
        event.preventDefault();
        this.pay('CASH');
        break;
      case 'F9':
        event.preventDefault();
        void this.checkout();
        break;
      case 'Escape':
        if (!this.term) {
          this.cart.clear();
        }
        break;
    }
  }

  /** Commits the sale locally first (zero data loss), prints, then lets the outbox sync it. */
  protected async checkout(): Promise<void> {
    if (!this.cart.canCheckout() || this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      const profile = this.permissions.profile();
      if (!profile) {
        throw new Error('Profil utilisateur indisponible : reconnectez-vous');
      }
      const db = this.databases.db();
      const occurredAt = new Date();
      const applied = this.cart.appliedPayments();
      const prescription = this.cart.prescription();
      let ticketNumber = '';
      await db.transaction('rw', db.meta, db.outbox, db.products, async () => {
        ticketNumber = await this.terminal.nextTicketNumber();
        await this.outbox.enqueue({
          clientSaleId: uuidv7(occurredAt.getTime()),
          terminalId: this.terminal.terminalId,
          ticketNumber,
          occurredAt: occurredAt.toISOString(),
          cashierUserId: profile.userId,
          lines: this.cart.lines().map((l) => ({
            productId: l.productId,
            quantity: l.quantity,
            unitPriceCents: l.unitPriceCents,
            discountCents: l.discountCents,
          })),
          payments: applied.map((p) => ({
            method: p.method,
            amountCents: p.amountCents,
            thirdPartyCode: p.thirdPartyCode,
          })),
          prescription: this.cart.requiresPrescription() && prescription ? prescription : undefined,
        });
      });
      this.receipt.set({
        ticketNumber,
        terminalId: this.terminal.terminalId,
        occurredAt,
        cashier: this.cashierName(),
        lines: this.cart.lines(),
        payments: this.cart.payments(),
        grossCents: this.cart.grossCents(),
        discountCents: this.cart.discountCents(),
        netCents: this.cart.netCents(),
        changeDueCents: this.cart.changeDueCents(),
        vat: this.cart.vatBreakdown(),
        offline: !this.connectivity.online(),
      });
      this.cart.clear();
      setTimeout(() => window.print());
      void this.outbox.flush();
    } catch (error) {
      this.message.set(`Vente non enregistrée : ${(error as Error).message}`);
    } finally {
      this.busy.set(false);
      this.searchInput().nativeElement.focus();
    }
  }
}
