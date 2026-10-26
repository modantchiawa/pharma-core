import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MOBILE_MONEY_PROVIDERS, PendingPayment } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';

type PaymentStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED';

const STATUS_LABELS: Record<PaymentStatus, string> = {
  PENDING: 'En attente du relevé',
  CONFIRMED: 'Confirmés',
  REJECTED: 'Rejetés',
};

/** Mobile money and card receipts ticked against the operator's statement. */
@Component({
  selector: 'app-payments-page',
  imports: [FormsModule, DatePipe, MoneyPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Paiements</h1>
          <p class="page-subtitle">
            Pointez les encaissements mobile money et carte sur le relevé de l'opérateur.
          </p>
        </div>
        <button type="button" class="btn btn-secondary" (click)="load()">
          <app-icon name="refresh" [size]="16" />
          Actualiser
        </button>
      </header>

      <div class="mb-5 grid gap-4 sm:grid-cols-2">
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="mobileMoney" [size]="16" /></span>Paiements
          </div>
          <div class="kpi-value">{{ payments().length }}</div>
          <div class="kpi-foot">{{ statusLabels[status] }}</div>
        </div>
        <div class="card kpi">
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>Montant total
          </div>
          <div class="kpi-value">{{ total() | money }}</div>
          <div class="kpi-foot">{{ provider || 'Tous (mobile money et carte)' }}</div>
        </div>
      </div>

      @if (message(); as text) {
        <div class="alert alert-info mb-4" role="status">
          <app-icon name="info" [size]="18" />
          <span>{{ text }}</span>
        </div>
      }

      <div class="card">
        <div class="card-header flex-wrap">
          <div
            class="segmented max-w-full overflow-x-auto [&>button]:whitespace-nowrap"
            role="tablist"
            aria-label="État"
          >
            @for (option of statusOptions; track option) {
              <button
                type="button"
                role="tab"
                [attr.aria-selected]="status === option"
                (click)="status = option; load()"
              >
                {{ statusLabels[option] }}
              </button>
            }
          </div>
          <label class="flex w-full items-center gap-2 text-sm sm:w-auto">
            <span class="text-muted">Opérateur</span>
            <select class="select sm:w-64" [(ngModel)]="provider" name="provider" (change)="load()">
              <option value="">Tous (mobile money et carte)</option>
              @for (p of providers; track p) {
                <option [value]="p">{{ p }}</option>
              }
            </select>
          </label>
        </div>
        @if (payments().length > 0) {
          <div class="overflow-x-auto">
            <table class="data-table whitespace-nowrap">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Ticket</th>
                  <th>Mode</th>
                  <th>Référence</th>
                  <th class="text-right">Montant</th>
                  <th class="text-right">Relevé</th>
                </tr>
              </thead>
              <tbody>
                @for (p of payments(); track p.paymentId) {
                  <tr>
                    <td class="whitespace-nowrap tabular-nums text-muted">
                      {{ p.occurredAt | date: 'dd/MM HH:mm' }}
                    </td>
                    <td class="font-medium">{{ p.ticketNumber }}</td>
                    <td>
                      <span class="inline-flex items-center gap-1.5">
                        <app-icon
                          [name]="p.method === 'CARD' ? 'card' : 'mobileMoney'"
                          [size]="15"
                          class="text-subtle"
                        />
                        {{ p.provider ?? p.method }}
                      </span>
                    </td>
                    <td class="font-mono text-xs">{{ p.reference ?? '—' }}</td>
                    <td class="num font-medium">{{ p.amountCents | money }}</td>
                    <td>
                      <div class="flex items-center justify-end gap-1">
                        @if (p.status === 'PENDING') {
                          <button
                            type="button"
                            class="btn btn-secondary btn-sm"
                            (click)="confirm(p)"
                          >
                            <app-icon name="check" [size]="16" class="text-success" />
                            Sur le relevé
                          </button>
                          <button
                            type="button"
                            class="btn btn-ghost btn-sm text-danger"
                            (click)="reject(p)"
                          >
                            <app-icon name="x" [size]="16" />
                            Absent
                          </button>
                        } @else if (p.status === 'REJECTED') {
                          <span class="badge badge-danger">
                            <app-icon name="x" [size]="12" />{{ p.rejectionReason }}
                          </span>
                        } @else if (p.status === 'CONFIRMED') {
                          <span class="badge badge-success">
                            <app-icon name="check" [size]="12" />Confirmé
                          </span>
                        }
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <div class="empty-state">
            <app-icon name="mobileMoney" [size]="22" />
            <p>Aucun paiement</p>
          </div>
        }
      </div>
    </div>
  `,
})
export class PaymentsPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/payments`;

  protected readonly providers = MOBILE_MONEY_PROVIDERS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly statusOptions = Object.keys(STATUS_LABELS) as PaymentStatus[];
  protected readonly payments = signal<PendingPayment[]>([]);
  protected readonly message = signal<string | null>(null);
  protected provider = '';
  protected status: PaymentStatus = 'PENDING';

  constructor() {
    void this.load();
  }

  protected total(): number {
    return this.payments().reduce((sum, p) => sum + p.amountCents, 0);
  }

  protected async load(): Promise<void> {
    const params: Record<string, string> = { status: this.status };
    if (this.provider) {
      params['provider'] = this.provider;
    }
    try {
      this.payments.set(
        await firstValueFrom(this.http.get<PendingPayment[]>(`${this.api}/pending`, { params })),
      );
    } catch {
      this.payments.set([]);
    }
  }

  protected async confirm(p: PendingPayment): Promise<void> {
    await this.act(() =>
      firstValueFrom(this.http.post<PendingPayment>(`${this.api}/${p.paymentId}/confirmation`, {})),
    );
  }

  protected async reject(p: PendingPayment): Promise<void> {
    const reason = prompt(`Motif du rejet du paiement ${p.reference ?? ''}`);
    if (!reason) {
      return;
    }
    await this.act(() =>
      firstValueFrom(
        this.http.post<PendingPayment>(`${this.api}/${p.paymentId}/rejection`, { reason }),
      ),
    );
  }

  private async act(action: () => Promise<PendingPayment>): Promise<void> {
    this.message.set(null);
    try {
      await action();
      await this.load();
    } catch (error) {
      this.message.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Opération refusée',
      );
    }
  }
}
