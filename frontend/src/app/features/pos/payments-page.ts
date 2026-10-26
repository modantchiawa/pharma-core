import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MOBILE_MONEY_PROVIDERS, PendingPayment } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';

/** Mobile money and card receipts ticked against the operator's statement. */
@Component({
  selector: 'app-payments-page',
  imports: [FormsModule, DatePipe, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <div class="flex flex-wrap items-end gap-2">
        <label class="text-sm"
          >Opérateur
          <select class="field" [(ngModel)]="provider" name="provider" (change)="load()">
            <option value="">Tous (mobile money et carte)</option>
            @for (p of providers; track p) {
              <option [value]="p">{{ p }}</option>
            }
          </select>
        </label>
        <label class="text-sm"
          >État
          <select class="field" [(ngModel)]="status" name="status" (change)="load()">
            <option value="PENDING">En attente du relevé</option>
            <option value="CONFIRMED">Confirmés</option>
            <option value="REJECTED">Rejetés</option>
          </select>
        </label>
        <p class="ml-auto text-sm text-slate-600">
          {{ payments().length }} paiement(s) · {{ total() | money }}
        </p>
      </div>
      <table class="w-full rounded border bg-white text-sm">
        <thead class="bg-slate-100 text-left">
          <tr>
            <th class="p-2">Date</th>
            <th class="p-2">Ticket</th>
            <th class="p-2">Mode</th>
            <th class="p-2">Référence</th>
            <th class="p-2 text-right">Montant</th>
            <th class="p-2"></th>
          </tr>
        </thead>
        <tbody>
          @for (p of payments(); track p.paymentId) {
            <tr class="border-t">
              <td class="p-2">{{ p.occurredAt | date: 'dd/MM HH:mm' }}</td>
              <td class="p-2">{{ p.ticketNumber }}</td>
              <td class="p-2">{{ p.provider ?? p.method }}</td>
              <td class="p-2 font-mono">{{ p.reference ?? '—' }}</td>
              <td class="p-2 text-right">{{ p.amountCents | money }}</td>
              <td class="p-2 text-right">
                @if (p.status === 'PENDING') {
                  <button
                    type="button"
                    class="rounded bg-green-700 px-2 py-0.5 text-white"
                    (click)="confirm(p)"
                  >
                    Sur le relevé
                  </button>
                  <button
                    type="button"
                    class="ml-1 rounded border border-red-700 px-2 py-0.5 text-red-700"
                    (click)="reject(p)"
                  >
                    Absent
                  </button>
                } @else if (p.status === 'REJECTED') {
                  <span class="text-red-700">{{ p.rejectionReason }}</span>
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" class="p-4 text-center text-slate-500">Aucun paiement</td>
            </tr>
          }
        </tbody>
      </table>
      @if (message(); as text) {
        <p class="text-sm" role="status">{{ text }}</p>
      }
    </div>
  `,
  styles: `
    .field {
      display: block;
      margin-top: 0.25rem;
      border: 1px solid #cbd5e1;
      border-radius: 0.375rem;
      padding: 0.375rem 0.5rem;
    }
  `,
})
export class PaymentsPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/payments`;

  protected readonly providers = MOBILE_MONEY_PROVIDERS;
  protected readonly payments = signal<PendingPayment[]>([]);
  protected readonly message = signal<string | null>(null);
  protected provider = '';
  protected status: 'PENDING' | 'CONFIRMED' | 'REJECTED' = 'PENDING';

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
