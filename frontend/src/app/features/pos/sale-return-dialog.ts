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
import { SaleReturnResult } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { FourEyesThresholdsService } from '../../core/control/four-eyes.service';
import { PosDatabaseService } from '../../core/offline/pos-database.service';
import { ToastService } from '../../core/ui/toast.service';
import { approverFields, isFourEyesRefusal, secondPersonNeeded } from '../../shared/four-eyes';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { SecondApprovalFields } from '../../shared/ui/second-approval';
import { returnRefund } from '../control/control-format';
import { errorDetail } from '../procurement/purchase-orders';

interface ReturnDetail {
  summary: { ticketNumber: string };
  lines: {
    lineNo: number;
    productId: string;
    quantity: number;
    netCents: number;
    returnedQuantity: number;
  }[];
  payments: { method: string }[];
}

interface ReturnLine {
  lineNo: number;
  designation: string;
  narcotic: boolean;
  netCents: number;
  sold: number;
  returnable: number;
  quantity: number;
}

/**
 * Partial return of a paid ticket: the units chosen go back into stock and the customer is
 * refunded in cash from this drawer. Above the refund threshold, or for a narcotic, a second
 * person signs inline. Tickets on credit or with a third-party payer are cancelled instead.
 */
@Component({
  selector: 'app-sale-return-dialog',
  imports: [FormsModule, MoneyPipe, Icon, SecondApprovalFields],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'closed.emit()' },
  template: `
    <div
      class="fixed inset-0 z-50 flex items-end justify-center bg-[rgb(2_6_12/0.5)] backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="return-title"
    >
      <form
        class="card flex max-h-[92vh] w-full max-w-lg flex-col rounded-b-none shadow-[var(--shadow-lg)] animate-in sm:rounded-b-[var(--radius-lg)]"
        (ngSubmit)="submit()"
      >
        <div class="card-header">
          <h3 id="return-title" class="card-title">Retour sur le ticket {{ ticket() }}</h3>
          <span class="badge tabular-nums">{{ refundCents() | money }}</span>
        </div>
        <div class="card-body space-y-3 overflow-y-auto">
          @if (blocked(); as reason) {
            <div class="alert alert-warning"><app-icon name="alert" [size]="16" />{{ reason }}</div>
          } @else {
            <ul class="divide-y divide-[var(--border)]" aria-label="Lignes du ticket">
              @for (line of lines(); track line.lineNo) {
                <li class="flex items-center gap-3 py-2.5">
                  <div class="min-w-0 flex-1">
                    <p class="truncate text-sm font-medium">
                      {{ line.designation }}
                      @if (line.narcotic) {
                        <span class="badge badge-danger ml-1">stupéfiant</span>
                      }
                    </p>
                    <p class="text-xs text-subtle tabular-nums">
                      {{ line.sold }} vendu(s) · {{ line.netCents | money }}
                      @if (line.sold !== line.returnable) {
                        · {{ line.sold - line.returnable }} déjà retourné(s)
                      }
                    </p>
                  </div>
                  <input
                    class="input num w-20"
                    type="number"
                    min="0"
                    [max]="line.returnable"
                    [disabled]="line.returnable === 0"
                    [attr.aria-label]="'Quantité retournée, ' + line.designation"
                    [ngModel]="line.quantity"
                    (ngModelChange)="setQuantity(line.lineNo, $event)"
                    [ngModelOptions]="{ standalone: true }"
                  />
                </li>
              }
            </ul>
            <label class="block">
              <span class="label">Motif du retour</span>
              <input
                class="input"
                name="reason"
                maxlength="255"
                required
                placeholder="Ex. traitement changé par le médecin"
                [ngModel]="reason()"
                (ngModelChange)="reason.set($event)"
              />
            </label>
            @if (needsApproval()) {
              <app-second-approval [why]="why()" [(email)]="approverEmail" [(pin)]="approverPin" />
            }
            <p class="hint">Le remboursement sort en espèces de la caisse de ce poste.</p>
          }
        </div>
        <div
          class="flex flex-col-reverse gap-2 border-t border-line px-5 py-3 sm:flex-row sm:justify-end"
        >
          <button type="button" class="btn btn-secondary" (click)="closed.emit()">Fermer</button>
          <button type="submit" class="btn btn-primary" [disabled]="!valid() || busy()">
            <app-icon name="receipt" [size]="16" />
            Rembourser {{ refundCents() | money }}
          </button>
        </div>
      </form>
    </div>
  `,
})
export class SaleReturnDialog implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/sales`;
  private readonly toast = inject(ToastService);
  private readonly fourEyes = inject(FourEyesThresholdsService);
  private readonly databases = inject(PosDatabaseService);

  readonly saleId = input.required<string>();
  readonly ticket = input.required<string>();
  readonly terminalId = input.required<string>();
  readonly done = output<SaleReturnResult>();
  readonly closed = output<void>();

  protected readonly lines = signal<ReturnLine[]>([]);
  protected readonly blocked = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly approverEmail = signal('');
  protected readonly approverPin = signal('');
  protected readonly busy = signal(false);
  private readonly approvalForced = signal(false);

  protected readonly refundCents = computed(() =>
    this.lines().reduce((sum, l) => sum + returnRefund(l.netCents, l.sold, l.quantity), 0),
  );
  private readonly narcotic = computed(() =>
    this.lines().some((l) => l.narcotic && l.quantity > 0),
  );
  protected readonly needsApproval = computed(
    () =>
      this.approvalForced() ||
      secondPersonNeeded(this.fourEyes.thresholds(), 'REFUND', this.refundCents(), this.narcotic()),
  );
  protected readonly why = computed(() =>
    this.narcotic() ? 'Retour d’un stupéfiant' : 'Remboursement au-delà du seuil',
  );
  protected readonly valid = computed(
    () =>
      this.refundCents() > 0 &&
      this.reason().trim().length > 0 &&
      this.lines().every((l) => l.quantity >= 0 && l.quantity <= l.returnable) &&
      (!this.needsApproval() ||
        (this.approverEmail().trim() !== '' && this.approverPin().trim() !== '')),
  );

  async ngOnInit(): Promise<void> {
    void this.fourEyes.load();
    try {
      const detail = await firstValueFrom(
        this.http.get<ReturnDetail>(`${this.api}/${this.saleId()}`),
      );
      if (detail.payments.some((p) => p.method === 'CREDIT' || p.method === 'THIRD_PARTY_PAYER')) {
        this.blocked.set(
          'Ce ticket a été réglé à crédit ou en tiers payant : annulez-le plutôt que de le retourner.',
        );
        return;
      }
      const products = this.databases.db().products;
      this.lines.set(
        await Promise.all(
          detail.lines.map(async (line) => {
            const cached = await products.get(line.productId);
            return {
              lineNo: line.lineNo,
              designation: cached?.designation ?? `Produit ${line.productId.slice(0, 8)}`,
              narcotic: cached?.listing === 'STUPEFIANT',
              netCents: line.netCents,
              sold: line.quantity,
              returnable: line.quantity - (line.returnedQuantity ?? 0),
              quantity: 0,
            };
          }),
        ),
      );
    } catch (error) {
      this.toast.error(errorDetail(error, 'Ticket introuvable'));
      this.closed.emit();
    }
  }

  protected setQuantity(lineNo: number, value: number | null): void {
    this.lines.update((lines) =>
      lines.map((l) => (l.lineNo === lineNo ? { ...l, quantity: Number(value ?? 0) } : l)),
    );
  }

  protected async submit(): Promise<void> {
    if (!this.valid() || this.busy()) {
      return;
    }
    this.busy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<SaleReturnResult>(`${this.api}/${this.saleId()}/returns`, {
          lines: this.lines()
            .filter((l) => l.quantity > 0)
            .map((l) => ({ lineNo: l.lineNo, quantity: l.quantity })),
          reason: this.reason().trim(),
          terminalId: this.terminalId(),
          ...approverFields({ email: this.approverEmail(), pin: this.approverPin() }),
        }),
      );
      this.done.emit(result);
    } catch (error) {
      if (isFourEyesRefusal(error)) {
        this.approvalForced.set(true);
        this.approverPin.set('');
      }
      this.toast.error(errorDetail(error, 'Retour refusé'));
    } finally {
      this.busy.set(false);
    }
  }
}
