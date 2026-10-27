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
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';
import { SkeletonRows } from '../../shared/ui/skeleton-rows';

/** Customer files, credit accounts ("ardoises") and settlements. */
@Component({
  selector: 'app-customers-page',
  imports: [FormsModule, MoneyPipe, HasPermissionDirective, Icon, EmptyState, SkeletonRows],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Clients</h1>
          <p class="page-subtitle">
            Fiches clients, comptes crédit (ardoises) et encaissement des créances.
          </p>
        </div>
        <button
          *appHasPermission="'CUSTOMER_MANAGE'"
          type="button"
          class="btn btn-primary"
          (click)="reset()"
        >
          <app-icon name="plus" [size]="16" />
          Nouveau client
        </button>
      </header>

      <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
        <section class="card min-w-0 self-start">
          <div class="card-header flex-wrap">
            <label class="input-with-icon min-w-0 flex-1 basis-60">
              <app-icon name="search" [size]="16" />
              <input
                type="search"
                class="input"
                placeholder="Nom, téléphone ou code"
                [(ngModel)]="term"
                (keydown.enter)="search()"
              />
            </label>
            <div class="flex gap-2">
              <button type="button" class="btn btn-secondary" (click)="search()">Rechercher</button>
              <button type="button" class="btn btn-ghost" (click)="debtors()">
                <app-icon name="credit" [size]="16" />
                Débiteurs
              </button>
            </div>
          </div>
          @if (!loaded()) {
            <app-skeleton-rows [rows]="6" [figures]="3" [thumb]="true" />
          } @else if (list().length > 0) {
            <div class="content-in overflow-x-auto">
              <table class="data-table whitespace-nowrap">
                <thead>
                  <tr>
                    <th>Client</th>
                    <th>Téléphone</th>
                    <th class="text-right">Dû</th>
                    <th class="text-right">Plafond</th>
                    <th class="text-right">Points</th>
                  </tr>
                </thead>
                <tbody>
                  @for (c of list(); track c.id) {
                    <tr
                      class="cursor-pointer"
                      [class.bg-brand-soft]="selected()?.id === c.id"
                      (click)="selected.set(c)"
                    >
                      <td>
                        <div class="flex items-center gap-3">
                          <span
                            class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-3 text-xs font-bold text-muted"
                            [class.!bg-brand]="selected()?.id === c.id"
                            [class.!text-on-brand]="selected()?.id === c.id"
                            >{{ c.displayName.charAt(0) }}</span
                          >
                          <div class="min-w-0">
                            <p class="truncate font-medium">{{ c.displayName }}</p>
                            <p class="text-xs text-subtle">{{ c.code }}</p>
                          </div>
                        </div>
                      </td>
                      <td class="whitespace-nowrap text-muted">{{ c.phone }}</td>
                      <td class="num">
                        @if (c.creditBalanceCents > 0) {
                          <span class="font-semibold text-danger">{{
                            c.creditBalanceCents | money
                          }}</span>
                        } @else {
                          <span class="text-subtle">{{ c.creditBalanceCents | money }}</span>
                        }
                      </td>
                      <td class="num">{{ c.creditLimitCents | money }}</td>
                      <td class="num text-muted">{{ c.loyaltyPoints }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <app-empty-state illustration="people">
              <p>Aucun client</p>
              <button
                *appHasPermission="'CUSTOMER_MANAGE'"
                type="button"
                class="btn btn-secondary btn-sm mt-1"
                (click)="reset()"
              >
                <app-icon name="plus" [size]="14" />
                Nouveau client
              </button>
            </app-empty-state>
          }
        </section>

        <aside class="space-y-5" *appHasPermission="'CUSTOMER_MANAGE'">
          @if (status(); as text) {
            <div class="alert alert-info" role="status">
              <app-icon name="info" [size]="18" />
              <span>{{ text }}</span>
            </div>
          }
          <form class="card" (ngSubmit)="save()">
            <div class="card-header">
              <h3 class="card-title">{{ selected() ? 'Modifier' : 'Nouveau client' }}</h3>
              @if (selected(); as c) {
                <span class="badge">{{ c.code }}</span>
              } @else {
                <app-icon name="user" [size]="18" class="text-subtle" />
              }
            </div>
            <div class="card-body space-y-3">
              <label class="block">
                <span class="label">Nom</span>
                <input
                  class="input"
                  placeholder="Nom"
                  [(ngModel)]="form.displayName"
                  name="name"
                  required
                  maxlength="150"
                />
              </label>
              <label class="block">
                <span class="label">Téléphone</span>
                <input
                  class="input"
                  placeholder="Téléphone"
                  [(ngModel)]="form.phone"
                  name="phone"
                  maxlength="30"
                />
              </label>
              <label class="block">
                <span class="label">Adresse</span>
                <input
                  class="input"
                  placeholder="Adresse"
                  [(ngModel)]="form.address"
                  name="address"
                  maxlength="300"
                />
              </label>
              <label class="block">
                <span class="label">Plafond de crédit</span>
                <input
                  inputmode="decimal"
                  class="input text-right tabular-nums"
                  [(ngModel)]="form.creditLimit"
                  name="limit"
                  placeholder="0"
                />
              </label>
              <label class="block">
                <span class="label">Notes</span>
                <textarea
                  class="textarea"
                  rows="2"
                  placeholder="Notes"
                  [(ngModel)]="form.notes"
                  name="notes"
                ></textarea>
              </label>
              <div class="flex gap-2 pt-1">
                <button type="submit" class="btn btn-primary flex-1">
                  <app-icon name="check" [size]="16" />
                  Enregistrer
                </button>
                <button type="button" class="btn btn-secondary" (click)="reset()">Nouveau</button>
              </div>
            </div>
          </form>
          @if (selected(); as c) {
            @if (c.creditBalanceCents > 0) {
              <form class="card" (ngSubmit)="pay(c)">
                <div class="card-header">
                  <h3 class="card-title">Encaisser une créance</h3>
                  <span class="badge badge-danger">dû {{ c.creditBalanceCents | money }}</span>
                </div>
                <div class="card-body space-y-3">
                  <label class="block">
                    <span class="label">Montant</span>
                    <input
                      inputmode="decimal"
                      class="input text-right tabular-nums"
                      placeholder="Montant"
                      [(ngModel)]="payAmount"
                      name="amount"
                      required
                    />
                  </label>
                  <div class="grid gap-3" [class.grid-cols-2]="payMethod === 'MOBILE_MONEY'">
                    <label class="block">
                      <span class="label">Mode</span>
                      <select class="select" [(ngModel)]="payMethod" name="method">
                        <option value="CASH">Espèces</option>
                        <option value="MOBILE_MONEY">Mobile money</option>
                        <option value="CARD">Carte</option>
                        <option value="CHECK">Chèque</option>
                      </select>
                    </label>
                    @if (payMethod === 'MOBILE_MONEY') {
                      <label class="block">
                        <span class="label">Opérateur</span>
                        <select class="select" [(ngModel)]="provider" name="provider">
                          @for (p of providers; track p) {
                            <option [value]="p">{{ p }}</option>
                          }
                        </select>
                      </label>
                    }
                  </div>
                  <input
                    class="input"
                    placeholder="Référence (optionnel)"
                    [(ngModel)]="reference"
                    name="reference"
                    maxlength="64"
                  />
                  <button type="submit" class="btn btn-primary w-full">
                    <app-icon name="credit" [size]="16" />
                    Encaisser
                  </button>
                </div>
              </form>
            }
          }
        </aside>
      </div>
    </div>
  `,
})
export class CustomersPage {
  private readonly http = inject(HttpClient);
  private readonly currency = inject(CurrencyService);
  private readonly terminal = inject(TerminalService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/customers`;

  protected term = '';
  protected readonly list = signal<Customer[]>([]);
  protected readonly loaded = signal(false);
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
    try {
      this.list.set(await firstValueFrom(this.http.get<Customer[]>(`${this.api}/debtors`)));
    } finally {
      this.loaded.set(true);
    }
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
