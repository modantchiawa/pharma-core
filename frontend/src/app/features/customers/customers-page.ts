import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Customer, MOBILE_MONEY_PROVIDERS, PaymentMethod } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { TerminalService } from '../../core/offline/terminal.service';
import { CurrencyService } from '../../core/ui/currency.service';
import { MoneyPipe } from '../../shared/money.pipe';

/** Customer files, credit accounts ("ardoises") and settlements. */
@Component({
  selector: 'app-customers-page',
  imports: [FormsModule, MoneyPipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-4 p-4 lg:grid-cols-[1fr_380px]">
      <section class="space-y-3">
        <div class="flex gap-2">
          <input
            type="search"
            class="flex-1 rounded border px-3 py-2"
            placeholder="Nom, téléphone ou code"
            [(ngModel)]="term"
            (keydown.enter)="search()"
          />
          <button type="button" class="rounded border px-3" (click)="search()">Rechercher</button>
          <button type="button" class="rounded border px-3" (click)="debtors()">Débiteurs</button>
        </div>
        <table class="w-full rounded border bg-white text-sm">
          <thead class="bg-slate-100 text-left">
            <tr>
              <th class="p-2">Client</th>
              <th class="p-2">Téléphone</th>
              <th class="p-2 text-right">Dû</th>
              <th class="p-2 text-right">Plafond</th>
              <th class="p-2 text-right">Points</th>
            </tr>
          </thead>
          <tbody>
            @for (c of list(); track c.id) {
              <tr
                class="cursor-pointer border-t hover:bg-slate-50"
                [class.bg-brand-dark]="selected()?.id === c.id"
                (click)="selected.set(c)"
              >
                <td class="p-2">
                  {{ c.displayName }} <small class="text-slate-500">{{ c.code }}</small>
                </td>
                <td class="p-2">{{ c.phone }}</td>
                <td class="p-2 text-right" [class.text-red-700]="c.creditBalanceCents > 0">
                  {{ c.creditBalanceCents | money }}
                </td>
                <td class="p-2 text-right">{{ c.creditLimitCents | money }}</td>
                <td class="p-2 text-right">{{ c.loyaltyPoints }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="5" class="p-4 text-center text-slate-500">Aucun client</td>
              </tr>
            }
          </tbody>
        </table>
      </section>

      <aside class="space-y-4" *appHasPermission="'CUSTOMER_MANAGE'">
        <form class="space-y-2 rounded-lg border bg-white p-4" (ngSubmit)="save()">
          <h3 class="font-semibold">{{ selected() ? 'Modifier' : 'Nouveau client' }}</h3>
          <input
            class="field"
            placeholder="Nom"
            [(ngModel)]="form.displayName"
            name="name"
            required
            maxlength="150"
          />
          <input
            class="field"
            placeholder="Téléphone"
            [(ngModel)]="form.phone"
            name="phone"
            maxlength="30"
          />
          <input
            class="field"
            placeholder="Adresse"
            [(ngModel)]="form.address"
            name="address"
            maxlength="300"
          />
          <label class="block text-sm"
            >Plafond de crédit<input
              inputmode="decimal"
              class="field"
              [(ngModel)]="form.creditLimit"
              name="limit"
          /></label>
          <textarea
            class="field"
            rows="2"
            placeholder="Notes"
            [(ngModel)]="form.notes"
            name="notes"
          ></textarea>
          <div class="flex gap-2">
            <button type="submit" class="rounded bg-brand px-3 py-1 text-on-brand">
              Enregistrer
            </button>
            <button type="button" class="rounded border px-3 py-1" (click)="reset()">
              Nouveau
            </button>
          </div>
        </form>
        @if (selected(); as c) {
          @if (c.creditBalanceCents > 0) {
            <form class="space-y-2 rounded-lg border bg-white p-4" (ngSubmit)="pay(c)">
              <h3 class="font-semibold">
                Encaisser une créance · dû {{ c.creditBalanceCents | money }}
              </h3>
              <input
                inputmode="decimal"
                class="field"
                placeholder="Montant"
                [(ngModel)]="payAmount"
                name="amount"
                required
              />
              <select class="field" [(ngModel)]="payMethod" name="method">
                <option value="CASH">Espèces</option>
                <option value="MOBILE_MONEY">Mobile money</option>
                <option value="CARD">Carte</option>
                <option value="CHECK">Chèque</option>
              </select>
              @if (payMethod === 'MOBILE_MONEY') {
                <select class="field" [(ngModel)]="provider" name="provider">
                  @for (p of providers; track p) {
                    <option [value]="p">{{ p }}</option>
                  }
                </select>
              }
              <input
                class="field"
                placeholder="Référence (optionnel)"
                [(ngModel)]="reference"
                name="reference"
                maxlength="64"
              />
              <button type="submit" class="w-full rounded bg-brand py-2 text-on-brand">
                Encaisser
              </button>
            </form>
          }
        }
        @if (status(); as text) {
          <p class="text-sm" role="status">{{ text }}</p>
        }
      </aside>
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
export class CustomersPage {
  private readonly http = inject(HttpClient);
  private readonly currency = inject(CurrencyService);
  private readonly terminal = inject(TerminalService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/customers`;

  protected term = '';
  protected readonly list = signal<Customer[]>([]);
  protected readonly selected = signal<Customer | null>(null);
  protected readonly status = signal<string | null>(null);
  protected readonly providers = MOBILE_MONEY_PROVIDERS;
  protected form = { displayName: '', phone: '', address: '', creditLimit: '', notes: '' };
  protected payAmount = '';
  protected payMethod: PaymentMethod = 'CASH';
  protected provider: string = MOBILE_MONEY_PROVIDERS[0];
  protected reference = '';

  constructor() {
    void this.debtors();
  }

  protected async search(): Promise<void> {
    if (this.term.trim().length < 2) {
      return;
    }
    this.list.set(
      await firstValueFrom(
        this.http.get<Customer[]>(this.api, { params: { q: this.term.trim(), limit: 50 } }),
      ),
    );
  }

  protected async debtors(): Promise<void> {
    this.list.set(await firstValueFrom(this.http.get<Customer[]>(`${this.api}/debtors`)));
  }

  protected reset(): void {
    this.selected.set(null);
    this.form = { displayName: '', phone: '', address: '', creditLimit: '', notes: '' };
  }

  protected async save(): Promise<void> {
    const body = {
      displayName: this.form.displayName,
      phone: this.form.phone || null,
      address: this.form.address || null,
      creditLimitCents: this.currency.parse(this.form.creditLimit),
      notes: this.form.notes || null,
    };
    try {
      const current = this.selected();
      const saved = current
        ? await firstValueFrom(this.http.put<Customer>(`${this.api}/${current.id}`, body))
        : await firstValueFrom(this.http.post<Customer>(this.api, body));
      this.status.set(`Client ${saved.code} enregistré`);
      this.selected.set(saved);
      this.list.update((items) => [saved, ...items.filter((c) => c.id !== saved.id)]);
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Enregistrement refusé',
      );
    }
  }

  protected async pay(customer: Customer): Promise<void> {
    try {
      const result = await firstValueFrom(
        this.http.post<{ remainingBalanceCents: number }>(`${this.api}/${customer.id}/payments`, {
          amountCents: this.currency.parse(this.payAmount),
          method: this.payMethod,
          provider: this.payMethod === 'MOBILE_MONEY' ? this.provider : null,
          reference: this.reference || null,
          terminalId: this.terminal.terminalId,
        }),
      );
      this.status.set('Règlement enregistré');
      this.payAmount = '';
      const updated = { ...customer, creditBalanceCents: result.remainingBalanceCents };
      this.selected.set(updated);
      this.list.update((items) => items.map((c) => (c.id === customer.id ? updated : c)));
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Règlement refusé',
      );
    }
  }
}
