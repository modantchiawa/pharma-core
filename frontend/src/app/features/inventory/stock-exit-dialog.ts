import { DatePipe } from '@angular/common';
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
import { ProductLots, WriteOffKind } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { FourEyesThresholdsService } from '../../core/control/four-eyes.service';
import { CurrencyService } from '../../core/ui/currency.service';
import { ToastService } from '../../core/ui/toast.service';
import {
  approverFields,
  isFourEyesRefusal,
  secondPersonNeeded,
  thresholdOf,
} from '../../shared/four-eyes';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { SecondApprovalFields } from '../../shared/ui/second-approval';
import { WRITE_OFF_KINDS } from '../control/control-format';
import { errorDetail } from '../procurement/purchase-orders';

type Mode = 'exit' | 'correction';

/**
 * Units that leave the stock without a sale (breakage, expiry noticed on the shelf, loss,
 * theft) or a lot corrected to what is on the shelf. The value at cost is shown as it is typed;
 * above the pharmacy's threshold, and always for a narcotic, a second person signs inline.
 */
@Component({
  selector: 'app-stock-exit-dialog',
  imports: [FormsModule, DatePipe, MoneyPipe, Icon, SecondApprovalFields],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'closed.emit()' },
  template: `
    <div
      class="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(2_6_12/0.5)] backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="exit-title"
    >
      <form
        class="card flex max-h-[92vh] w-full max-w-lg flex-col rounded-b-none shadow-[var(--shadow-lg)] animate-in sm:rounded-b-[var(--radius-lg)]"
        (ngSubmit)="submit()"
      >
        <div class="card-header">
          <div class="min-w-0">
            <h3 id="exit-title" class="card-title">Sortie ou correction de stock</h3>
            <p class="mt-0.5 truncate text-sm text-muted">
              {{ designation() }}
              @if (lots()?.narcotic) {
                <span class="badge badge-danger ml-1">stupéfiant</span>
              }
            </p>
          </div>
          <button
            type="button"
            class="btn btn-ghost btn-icon"
            aria-label="Fermer"
            (click)="closed.emit()"
          >
            <app-icon name="x" [size]="18" />
          </button>
        </div>
        <div class="card-body space-y-4 overflow-y-auto">
          <div class="segmented w-full" role="tablist" aria-label="Nature">
            <button
              type="button"
              role="tab"
              class="flex-1"
              [attr.aria-selected]="mode() === 'exit'"
              (click)="mode.set('exit')"
            >
              Sortie déclarée
            </button>
            <button
              type="button"
              role="tab"
              class="flex-1"
              [attr.aria-selected]="mode() === 'correction'"
              (click)="mode.set('correction')"
            >
              Correction de quantité
            </button>
          </div>

          @if (lots(); as l) {
            @if (l.lots.length === 0) {
              <div class="empty-state">
                <app-icon name="stock" [size]="22" />
                <p>Aucun lot en stock pour ce produit.</p>
              </div>
            } @else {
              <label class="block">
                <span class="label">Lot</span>
                <select
                  class="select"
                  name="lot"
                  [ngModel]="lotId()"
                  (ngModelChange)="lotId.set($event)"
                >
                  @for (lot of l.lots; track lot.lotId) {
                    <option [value]="lot.lotId">
                      {{ lot.lotNumber }} · {{ lot.expiryDate | date: 'MM/yyyy' }} ·
                      {{ lot.quantity }} en stock
                    </option>
                  }
                </select>
              </label>

              @if (mode() === 'exit') {
                <div>
                  <span class="label">Motif de sortie</span>
                  <div class="grid grid-cols-2 gap-2 sm:grid-cols-4" role="radiogroup">
                    @for (k of kinds; track k.kind) {
                      <button
                        type="button"
                        role="radio"
                        class="btn btn-secondary btn-sm"
                        [class.kind-on]="kind() === k.kind"
                        [attr.aria-checked]="kind() === k.kind"
                        (click)="kind.set(k.kind)"
                      >
                        {{ k.label }}
                      </button>
                    }
                  </div>
                </div>
              }

              <div class="grid gap-3 sm:grid-cols-2">
                <label class="block">
                  <span class="label">{{
                    mode() === 'exit' ? 'Unités sorties' : 'Quantité constatée en rayon'
                  }}</span>
                  <input
                    class="input num"
                    type="number"
                    min="0"
                    name="quantity"
                    required
                    [ngModel]="quantity()"
                    (ngModelChange)="quantity.set($event)"
                  />
                </label>
                <div class="rounded-[var(--radius)] bg-surface-2 p-3">
                  <p class="text-xs font-medium text-muted">Valeur au coût</p>
                  <p
                    class="mt-0.5 text-lg font-semibold tabular-nums"
                    [class.text-danger]="valueCents() < 0"
                  >
                    {{ valueCents() | money }}
                  </p>
                </div>
              </div>
              <label class="block">
                <span class="label">Motif détaillé</span>
                <input
                  class="input"
                  name="reason"
                  maxlength="255"
                  required
                  placeholder="Ex. flacon tombé à la mise en rayon"
                  [ngModel]="reason()"
                  (ngModelChange)="reason.set($event)"
                />
              </label>
              @if (problem(); as text) {
                <p class="flex items-center gap-1.5 text-xs text-danger">
                  <app-icon name="alert" [size]="13" />{{ text }}
                </p>
              }
              @if (needsApproval()) {
                <app-second-approval
                  [why]="why()"
                  [(email)]="approverEmail"
                  [(pin)]="approverPin"
                />
              }
            }
          } @else {
            <div class="skeleton h-24"></div>
          }
        </div>
        <div
          class="flex flex-col-reverse gap-2 border-t border-line px-5 py-3 sm:flex-row sm:justify-end"
        >
          <button type="button" class="btn btn-secondary" (click)="closed.emit()">Annuler</button>
          <button
            type="submit"
            class="btn"
            [class.btn-danger]="mode() === 'exit'"
            [class.btn-primary]="mode() === 'correction'"
            [disabled]="!valid() || busy()"
          >
            <app-icon [name]="mode() === 'exit' ? 'trash' : 'check'" [size]="16" />
            {{ mode() === 'exit' ? 'Sortir du stock' : 'Corriger le lot' }}
          </button>
        </div>
      </form>
    </div>
  `,
  styles: `
    .kind-on {
      border-color: var(--brand-primary);
      background: var(--brand-soft);
      color: var(--brand-text);
    }
  `,
})
export class StockExitDialog implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/inventory`;
  private readonly currency = inject(CurrencyService);
  private readonly toast = inject(ToastService);
  private readonly fourEyes = inject(FourEyesThresholdsService);

  readonly productId = input.required<string>();
  readonly designation = input.required<string>();
  readonly done = output<void>();
  readonly closed = output<void>();

  protected readonly kinds = WRITE_OFF_KINDS;
  protected readonly lots = signal<ProductLots | null>(null);
  protected readonly mode = signal<Mode>('exit');
  protected readonly lotId = signal('');
  protected readonly kind = signal<WriteOffKind>('CASSE');
  protected readonly quantity = signal<number | null>(null);
  protected readonly reason = signal('');
  protected readonly approverEmail = signal('');
  protected readonly approverPin = signal('');
  protected readonly busy = signal(false);
  /** The server asked for the second signature although the screen did not expect it. */
  private readonly approvalForced = signal(false);

  protected readonly lot = computed(() => this.lots()?.lots.find((l) => l.lotId === this.lotId()));
  /** Signed value at cost: negative for what leaves the shelf. */
  protected readonly valueCents = computed(() => {
    const lot = this.lot();
    const quantity = Number(this.quantity() ?? NaN);
    if (!lot || !Number.isFinite(quantity)) {
      return 0;
    }
    const delta = this.mode() === 'exit' ? -quantity : quantity - lot.quantity;
    return delta * lot.unitCostCents;
  });
  protected readonly problem = computed(() => {
    const lot = this.lot();
    const quantity = this.quantity();
    if (!lot || quantity === null || quantity === undefined || `${quantity}` === '') {
      return null;
    }
    if (this.mode() === 'exit' && (quantity <= 0 || quantity > lot.quantity)) {
      return `Entre 1 et ${lot.quantity} unité(s) pour ce lot`;
    }
    if (this.mode() === 'correction' && (quantity < 0 || Number(quantity) === lot.quantity)) {
      return 'Indiquez une quantité différente du stock actuel';
    }
    return null;
  });
  protected readonly needsApproval = computed(
    () =>
      this.approvalForced() ||
      secondPersonNeeded(
        this.fourEyes.thresholds(),
        'STOCK',
        this.valueCents(),
        this.lots()?.narcotic ?? false,
      ),
  );
  protected readonly why = computed(() => {
    if (this.lots()?.narcotic) {
      return 'Produit stupéfiant';
    }
    const threshold = thresholdOf(this.fourEyes.thresholds(), 'STOCK');
    const amount = new Intl.NumberFormat('fr-FR', {
      style: 'currency',
      currency: this.currency.current(),
    }).format(this.currency.toUnits(threshold));
    return threshold > 0 ? `Valeur au-delà de ${amount} au coût` : 'Opération sensible';
  });
  protected readonly valid = computed(
    () =>
      !!this.lot() &&
      this.quantity() !== null &&
      this.problem() === null &&
      this.reason().trim().length > 0 &&
      (!this.needsApproval() ||
        (this.approverEmail().trim() !== '' && this.approverPin().trim() !== '')),
  );

  async ngOnInit(): Promise<void> {
    void this.fourEyes.load();
    try {
      const lots = await firstValueFrom(
        this.http.get<ProductLots>(`${this.api}/stocks/${this.productId()}/lots`),
      );
      this.lots.set(lots);
      this.lotId.set(lots.lots[0]?.lotId ?? '');
    } catch (error) {
      this.toast.error(errorDetail(error, 'Lots introuvables'));
      this.closed.emit();
    }
  }

  protected async submit(): Promise<void> {
    const lot = this.lot();
    if (!lot || !this.valid() || this.busy()) {
      return;
    }
    const approval = approverFields({ email: this.approverEmail(), pin: this.approverPin() });
    this.busy.set(true);
    try {
      if (this.mode() === 'exit') {
        await firstValueFrom(
          this.http.post(`${this.api}/lots/${lot.lotId}/write-offs`, {
            kind: this.kind(),
            quantity: Number(this.quantity()),
            reason: this.reason().trim(),
            ...approval,
          }),
        );
        this.toast.success(`${this.quantity()} unité(s) sortie(s) du stock`);
      } else {
        await firstValueFrom(
          this.http.put(`${this.api}/lots/${lot.lotId}/quantity`, {
            countedQuantity: Number(this.quantity()),
            reason: this.reason().trim(),
            ...approval,
          }),
        );
        this.toast.success(`Lot ${lot.lotNumber} corrigé à ${this.quantity()} unité(s)`);
      }
      this.done.emit();
    } catch (error) {
      if (isFourEyesRefusal(error)) {
        this.approvalForced.set(true);
        this.approverPin.set('');
      }
      this.toast.error(errorDetail(error, 'Opération refusée'));
    } finally {
      this.busy.set(false);
    }
  }
}
