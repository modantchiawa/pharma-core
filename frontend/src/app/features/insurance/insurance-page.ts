import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  Customer,
  InsurancePolicy,
  Insurer,
  InsurerKind,
  InsurerReceivable,
  InsurerStatement,
  InsurerStatementDetail,
  MOBILE_MONEY_PROVIDERS,
  PaymentMethod,
  StatementStatus,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { CurrencyService } from '../../core/ui/currency.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';

type Tab = 'policies' | 'statements';

const KINDS: { code: InsurerKind; label: string }[] = [
  { code: 'MUTUELLE', label: 'Mutuelle' },
  { code: 'ASSURANCE', label: 'Assurance' },
  { code: 'ETAT', label: 'Régime public' },
  { code: 'ENTREPRISE', label: 'Convention entreprise' },
];

/** Third-party payers: insurers, their policies, the statements sent and the money awaited. */
@Component({
  selector: 'app-insurance-page',
  imports: [FormsModule, MoneyPipe, DatePipe, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Tiers payant</h1>
          <p class="page-subtitle">
            Assureurs, polices des assurés, bordereaux envoyés et règlements attendus.
          </p>
        </div>
        <button
          type="button"
          class="btn btn-primary"
          *appHasPermission="'INSURANCE_MANAGE'"
          (click)="newInsurer()"
        >
          <app-icon name="plus" [size]="16" />
          Nouvel assureur
        </button>
      </header>

      @if (status(); as text) {
        <div class="alert alert-info mb-4" role="status">
          <app-icon name="info" [size]="18" />
          <span>{{ text }}</span>
        </div>
      }

      <div class="grid gap-5 lg:grid-cols-[300px_minmax(0,1fr)]">
        <aside class="space-y-5">
          <section class="card">
            <div class="kpi">
              <div class="kpi-label">
                <span class="kpi-icon"><app-icon name="credit" [size]="16" /></span>Encours tiers
                payant
              </div>
              <div class="kpi-value">{{ receivablesTotal() | money }}</div>
              <div class="kpi-foot">{{ receivables().length }} assureur(s) avec un encours</div>
            </div>
            @if (receivables().length > 0) {
              <ul class="divide-y divide-[var(--border)] border-t border-line text-sm">
                @for (r of receivables(); track r.insurerId) {
                  <li class="flex items-center justify-between gap-3 px-5 py-2.5">
                    <span class="min-w-0 truncate text-muted">{{ r.insurerName }}</span>
                    <span class="num font-semibold">{{
                      r.billedUnpaidCents + r.unbilledCents | money
                    }}</span>
                  </li>
                }
              </ul>
            } @else {
              <p class="border-t border-line px-5 py-3 text-sm text-subtle">Aucun encours</p>
            }
          </section>

          <section class="card">
            <div class="card-header">
              <h2 class="card-title">Assureurs</h2>
              <span class="badge">{{ insurers().length }}</span>
            </div>
            <ul class="p-1.5">
              @for (i of insurers(); track i.id) {
                <li>
                  <button
                    type="button"
                    class="flex w-full items-center gap-3 rounded-[var(--radius-sm)] px-3 py-2 text-left text-sm transition-colors hover:bg-surface-3"
                    [class.bg-brand-soft]="selected()?.id === i.id"
                    [class.opacity-50]="!i.active"
                    (click)="select(i)"
                  >
                    <span
                      class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-surface-3 text-xs font-bold text-muted"
                      [class.!bg-brand]="selected()?.id === i.id"
                      [class.!text-on-brand]="selected()?.id === i.id"
                      >{{ i.details.name.charAt(0) }}</span
                    >
                    <span class="min-w-0 flex-1 truncate font-medium">{{ i.details.name }}</span>
                    <span class="badge">{{ i.details.defaultCoverageRate }} %</span>
                  </button>
                </li>
              } @empty {
                <li class="empty-state">
                  <app-icon name="insurance" [size]="22" />
                  <p>Aucun assureur</p>
                </li>
              }
            </ul>
          </section>
        </aside>

        <section class="min-w-0 space-y-5">
          @if (editing(); as form) {
            <form class="card" (ngSubmit)="saveInsurer()" *appHasPermission="'INSURANCE_MANAGE'">
              <div class="card-header">
                <h3 class="card-title">
                  {{ selected() ? 'Modifier ' + selected()!.details.name : 'Nouvel assureur' }}
                </h3>
                <app-icon name="building" [size]="18" class="text-subtle" />
              </div>
              <div class="card-body grid gap-4 md:grid-cols-2">
                <label class="block">
                  <span class="label">Nom</span>
                  <input
                    class="input"
                    placeholder="Nom"
                    [(ngModel)]="form.name"
                    name="name"
                    required
                    maxlength="150"
                  />
                </label>
                <label class="block">
                  <span class="label">Type</span>
                  <select class="select" [(ngModel)]="form.kind" name="kind">
                    @for (k of kinds; track k.code) {
                      <option [value]="k.code">{{ k.label }}</option>
                    }
                  </select>
                </label>
                <label class="block">
                  <span class="label">Taux de couverture par défaut (%)</span>
                  <input
                    type="number"
                    min="0"
                    max="100"
                    class="input"
                    [(ngModel)]="form.defaultCoverageRate"
                    name="rate"
                  />
                </label>
                <label class="block">
                  <span class="label">Délai de règlement (jours)</span>
                  <input
                    type="number"
                    min="0"
                    class="input"
                    [(ngModel)]="form.paymentTermsDays"
                    name="terms"
                  />
                </label>
                <label class="block">
                  <span class="label">Contact</span>
                  <input
                    class="input"
                    placeholder="Contact"
                    [(ngModel)]="form.contactName"
                    name="contact"
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
                  <span class="label">Courriel</span>
                  <input
                    class="input"
                    placeholder="Courriel"
                    [(ngModel)]="form.email"
                    name="email"
                    maxlength="200"
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
                <div class="flex flex-wrap gap-2 pt-1 md:col-span-2">
                  <button type="submit" class="btn btn-primary">
                    <app-icon name="check" [size]="16" />
                    Enregistrer
                  </button>
                  <button type="button" class="btn btn-secondary" (click)="editing.set(null)">
                    Annuler
                  </button>
                  @if (selected(); as i) {
                    <button
                      type="button"
                      class="btn btn-ghost ml-auto text-danger"
                      (click)="toggleActive(i)"
                    >
                      {{ i.active ? 'Désactiver' : 'Réactiver' }}
                    </button>
                  }
                </div>
              </div>
            </form>
          }

          @if (selected(); as insurer) {
            <div class="card">
              <div class="card-body flex flex-wrap items-center gap-4">
                <span
                  class="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-brand-soft text-brand"
                >
                  <app-icon name="insurance" [size]="20" />
                </span>
                <div class="min-w-0 flex-1">
                  <h2 class="flex flex-wrap items-center gap-2 text-lg font-semibold">
                    {{ insurer.details.name }}
                    @if (!insurer.active) {
                      <span class="badge">Inactif</span>
                    }
                  </h2>
                  <p class="text-sm text-muted">
                    {{ insurer.code }} · compte {{ insurer.accountCode }}
                  </p>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <nav class="segmented [&>button]:whitespace-nowrap" role="tablist">
                    <button
                      type="button"
                      role="tab"
                      [attr.aria-selected]="tab() === 'policies'"
                      (click)="tab.set('policies')"
                    >
                      Polices ({{ policies().length }})
                    </button>
                    <button
                      type="button"
                      role="tab"
                      [attr.aria-selected]="tab() === 'statements'"
                      (click)="tab.set('statements')"
                    >
                      Bordereaux ({{ statements().length }})
                    </button>
                  </nav>
                  <button
                    type="button"
                    class="btn btn-secondary btn-sm"
                    *appHasPermission="'INSURANCE_MANAGE'"
                    (click)="editInsurer(insurer)"
                  >
                    <app-icon name="pencil" [size]="15" />
                    Modifier
                  </button>
                </div>
              </div>
            </div>

            @switch (tab()) {
              @case ('policies') {
                <div class="card">
                  <div class="card-header">
                    <h3 class="card-title">Polices</h3>
                  </div>
                  @if (policies().length > 0) {
                    <div class="overflow-x-auto">
                      <table class="data-table whitespace-nowrap">
                        <thead>
                          <tr>
                            <th>Police</th>
                            <th>Bénéficiaire</th>
                            <th class="text-right">Taux</th>
                            <th class="whitespace-nowrap text-right">Plafond mensuel</th>
                            <th>Validité</th>
                            <th>État</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (p of policies(); track p.id) {
                            <tr>
                              <td class="whitespace-nowrap font-mono text-xs">
                                {{ p.policyNumber }}
                              </td>
                              <td class="font-medium">{{ p.beneficiaryName }}</td>
                              <td class="num whitespace-nowrap">{{ p.coverageRate }} %</td>
                              <td class="num">
                                {{
                                  p.monthlyCeilingCents === null
                                    ? '—'
                                    : (p.monthlyCeilingCents | money)
                                }}
                              </td>
                              <td class="whitespace-nowrap tabular-nums text-muted">
                                {{ p.validFrom | date: 'dd/MM/yyyy' }}
                                @if (p.validUntil) {
                                  → {{ p.validUntil | date: 'dd/MM/yyyy' }}
                                }
                              </td>
                              <td>
                                <span class="badge" [class.badge-success]="p.usable"
                                  ><span class="dot"></span
                                  >{{
                                    p.usable ? 'Active' : p.active ? 'Hors validité' : 'Suspendue'
                                  }}</span
                                >
                              </td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  } @else {
                    <div class="empty-state">
                      <app-icon name="insurance" [size]="22" />
                      <p>Aucune police</p>
                    </div>
                  }
                </div>
                <form
                  class="card"
                  (ngSubmit)="savePolicy(insurer)"
                  *appHasPermission="'INSURANCE_MANAGE'"
                >
                  <div class="card-header">
                    <h3 class="card-title">Nouvelle police</h3>
                    <app-icon name="plus" [size]="18" class="text-subtle" />
                  </div>
                  <div class="card-body grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                    <div class="relative sm:col-span-2 lg:col-span-3">
                      <span class="label">Assuré</span>
                      <label class="input-with-icon block">
                        <app-icon name="search" [size]="16" />
                        <input
                          type="search"
                          class="input"
                          placeholder="Client (nom, téléphone, code)"
                          [(ngModel)]="customerTerm"
                          (ngModelChange)="searchCustomers($event)"
                          name="customer"
                        />
                      </label>
                      @if (customerResults().length > 0) {
                        <ul
                          class="card absolute z-10 mt-1 w-full p-1 shadow-[var(--shadow-lg)]"
                          role="listbox"
                        >
                          @for (c of customerResults(); track c.id) {
                            <li
                              role="option"
                              class="flex cursor-pointer items-center justify-between gap-2 rounded-[var(--radius-sm)] px-3 py-2 text-sm hover:bg-surface-3"
                              (mousedown)="pickCustomer(c)"
                            >
                              <span class="font-medium">{{ c.displayName }}</span>
                              <small class="text-subtle">{{ c.phone }}</small>
                            </li>
                          }
                        </ul>
                      }
                      @if (policyCustomer(); as c) {
                        <p
                          class="mt-2 flex items-center gap-2 rounded-[var(--radius-sm)] bg-brand-soft px-3 py-2 text-sm"
                        >
                          <app-icon name="user" [size]="16" />
                          <span
                            >Assuré : <strong>{{ c.displayName }}</strong> ({{ c.code }})</span
                          >
                        </p>
                      }
                    </div>
                    <label class="block">
                      <span class="label">Numéro de police</span>
                      <input
                        class="input"
                        placeholder="Numéro de police"
                        [(ngModel)]="policyForm.policyNumber"
                        name="number"
                        required
                        maxlength="60"
                      />
                    </label>
                    <label class="block">
                      <span class="label">Bénéficiaire</span>
                      <input
                        class="input"
                        placeholder="Nom du bénéficiaire"
                        [(ngModel)]="policyForm.beneficiaryName"
                        name="beneficiary"
                        required
                        maxlength="150"
                      />
                    </label>
                    <label class="block">
                      <span class="label">Taux (%)</span>
                      <input
                        type="number"
                        min="0"
                        max="100"
                        class="input"
                        [(ngModel)]="policyForm.coverageRate"
                        name="prate"
                      />
                    </label>
                    <label class="block">
                      <span class="label">Plafond mensuel (vide = illimité)</span>
                      <input
                        inputmode="decimal"
                        class="input text-right tabular-nums"
                        [(ngModel)]="policyForm.monthlyCeiling"
                        name="ceiling"
                      />
                    </label>
                    <label class="block">
                      <span class="label">Début</span>
                      <input
                        type="date"
                        class="input"
                        [(ngModel)]="policyForm.validFrom"
                        name="from"
                        required
                      />
                    </label>
                    <label class="block">
                      <span class="label">Fin (facultatif)</span>
                      <input
                        type="date"
                        class="input"
                        [(ngModel)]="policyForm.validUntil"
                        name="until"
                      />
                    </label>
                    <div class="sm:col-span-2 lg:col-span-3">
                      <button type="submit" class="btn btn-primary">
                        <app-icon name="check" [size]="16" />
                        Enregistrer la police
                      </button>
                    </div>
                  </div>
                </form>
              }
              @case ('statements') {
                <div class="card">
                  <div class="card-header flex-wrap items-end">
                    <h3 class="card-title self-center">Bordereaux</h3>
                    <form
                      class="grid w-full grid-cols-2 items-end gap-3 sm:flex sm:w-auto sm:flex-wrap"
                      (ngSubmit)="generate(insurer)"
                      *appHasPermission="'INSURANCE_MANAGE'"
                    >
                      <label class="block">
                        <span class="label">Du</span>
                        <input
                          type="date"
                          class="input"
                          [(ngModel)]="periodFrom"
                          name="pfrom"
                          required
                        />
                      </label>
                      <label class="block">
                        <span class="label">au</span>
                        <input
                          type="date"
                          class="input"
                          [(ngModel)]="periodTo"
                          name="pto"
                          required
                        />
                      </label>
                      <button type="submit" class="btn btn-primary col-span-2">
                        <app-icon name="spreadsheet" [size]="16" />
                        Générer le bordereau
                      </button>
                    </form>
                  </div>
                  @if (statements().length > 0) {
                    <div class="overflow-x-auto">
                      <table class="data-table whitespace-nowrap">
                        <thead>
                          <tr>
                            <th>Numéro</th>
                            <th>Période</th>
                            <th class="text-right">Lignes</th>
                            <th class="text-right">Total</th>
                            <th class="text-right">Réglé</th>
                            <th>État</th>
                            <th></th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (s of statements(); track s.id) {
                            <tr [class.bg-brand-soft]="detail()?.statement.id === s.id">
                              <td>
                                <button
                                  type="button"
                                  class="font-semibold text-brand hover:underline"
                                  (click)="openStatement(s)"
                                >
                                  {{ s.number }}
                                </button>
                              </td>
                              <td class="whitespace-nowrap tabular-nums text-muted">
                                {{ s.periodFrom | date: 'dd/MM' }} →
                                {{ s.periodTo | date: 'dd/MM/yyyy' }}
                              </td>
                              <td class="num">{{ s.lineCount }}</td>
                              <td class="num font-medium">{{ s.totalCents | money }}</td>
                              <td class="num">{{ s.paidCents | money }}</td>
                              <td>
                                <span
                                  class="badge"
                                  [class.badge-info]="s.status === 'ISSUED'"
                                  [class.badge-success]="s.status === 'PAID'"
                                  ><span class="dot"></span>{{ statusLabel(s.status) }}</span
                                >
                              </td>
                              <td>
                                <div class="flex justify-end gap-1">
                                  <a class="btn btn-ghost btn-sm" [href]="exportUrl(s)" download>
                                    <app-icon name="download" [size]="15" />
                                    CSV
                                  </a>
                                  @if (s.status === 'DRAFT') {
                                    <button
                                      type="button"
                                      class="btn btn-soft btn-sm"
                                      *appHasPermission="'INSURANCE_MANAGE'"
                                      (click)="issue(s)"
                                    >
                                      <app-icon name="send" [size]="15" />
                                      Émettre
                                    </button>
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
                      <app-icon name="spreadsheet" [size]="22" />
                      <p>Aucun bordereau</p>
                    </div>
                  }
                </div>
                @if (detail(); as d) {
                  <section class="card">
                    <div class="card-header flex-wrap">
                      <h3 class="card-title">{{ d.statement.number }}</h3>
                      <span class="badge badge-warning">
                        reste dû {{ d.statement.remainingCents | money }}
                      </span>
                    </div>
                    <div class="overflow-x-auto">
                      <table class="data-table whitespace-nowrap">
                        <thead>
                          <tr>
                            <th>Ticket</th>
                            <th>Date</th>
                            <th>Police</th>
                            <th>Bénéficiaire</th>
                            <th class="text-right">Vente</th>
                            <th class="whitespace-nowrap text-right">Part assureur</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (l of d.lines; track l.saleId) {
                            <tr>
                              <td class="font-medium">{{ l.ticketNumber }}</td>
                              <td class="whitespace-nowrap tabular-nums text-muted">
                                {{ l.occurredAt | date: 'dd/MM HH:mm' }}
                              </td>
                              <td class="whitespace-nowrap font-mono text-xs">
                                {{ l.policyNumber }}
                              </td>
                              <td>{{ l.beneficiaryName }}</td>
                              <td class="num">{{ l.saleTotalCents | money }}</td>
                              <td class="num font-semibold">{{ l.amountCents | money }}</td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                    @if (d.statement.status === 'ISSUED') {
                      <form
                        class="grid gap-3 border-t border-line bg-surface-2 p-5 sm:grid-cols-2 xl:flex xl:flex-wrap xl:items-end"
                        (ngSubmit)="pay(d.statement)"
                        *appHasPermission="'INSURANCE_MANAGE'"
                      >
                        <label class="block">
                          <span class="label">Montant reçu</span>
                          <input
                            inputmode="decimal"
                            class="input text-right tabular-nums"
                            [(ngModel)]="payment.amount"
                            name="amount"
                            required
                          />
                        </label>
                        <label class="block">
                          <span class="label">Mode</span>
                          <select class="select" [(ngModel)]="payment.method" name="method">
                            <option value="MOBILE_MONEY">Mobile money</option>
                            <option value="CHECK">Chèque / virement</option>
                            <option value="CARD">Carte</option>
                          </select>
                        </label>
                        @if (payment.method === 'MOBILE_MONEY') {
                          <label class="block">
                            <span class="label">Opérateur</span>
                            <select class="select" [(ngModel)]="payment.provider" name="provider">
                              @for (p of providers; track p) {
                                <option [value]="p">{{ p.replace('_', ' ') }}</option>
                              }
                            </select>
                          </label>
                        }
                        <label class="block">
                          <span class="label">Référence</span>
                          <input
                            class="input"
                            [(ngModel)]="payment.reference"
                            name="ref"
                            maxlength="64"
                          />
                        </label>
                        <button type="submit" class="btn btn-primary sm:col-span-2">
                          <app-icon name="banknote" [size]="16" />
                          Enregistrer le règlement
                        </button>
                      </form>
                    }
                  </section>
                }
              }
            }
          } @else if (!editing()) {
            <div class="card">
              <div class="empty-state py-16">
                <app-icon name="insurance" [size]="22" />
                <p class="font-medium text-ink">Choisissez un assureur ou créez-en un.</p>
                <p class="text-subtle">Ses polices et bordereaux s'afficheront ici.</p>
              </div>
            </div>
          }
        </section>
      </div>
    </div>
  `,
})
export class InsurancePage {
  private readonly http = inject(HttpClient);
  private readonly currency = inject(CurrencyService);
  private readonly api = inject(APP_ENVIRONMENT).apiUrl;

  protected readonly kinds = KINDS;
  protected readonly providers = MOBILE_MONEY_PROVIDERS;
  protected readonly insurers = signal<Insurer[]>([]);
  protected readonly receivables = signal<InsurerReceivable[]>([]);
  protected readonly receivablesTotal = computed(() =>
    this.receivables().reduce((sum, r) => sum + r.billedUnpaidCents + r.unbilledCents, 0),
  );
  protected readonly selected = signal<Insurer | null>(null);
  protected readonly policies = signal<InsurancePolicy[]>([]);
  protected readonly statements = signal<InsurerStatement[]>([]);
  protected readonly detail = signal<InsurerStatementDetail | null>(null);
  protected readonly tab = signal<Tab>('policies');
  protected readonly status = signal<string | null>(null);
  protected readonly editing = signal<Insurer['details'] | null>(null);
  protected readonly customerResults = signal<Customer[]>([]);
  protected readonly policyCustomer = signal<Customer | null>(null);

  protected customerTerm = '';
  protected policyForm = {
    policyNumber: '',
    beneficiaryName: '',
    coverageRate: 80,
    monthlyCeiling: '',
    validFrom: new Date().toISOString().slice(0, 10),
    validUntil: '',
  };
  protected periodFrom = firstOfMonth();
  protected periodTo = new Date().toISOString().slice(0, 10);
  protected payment = {
    amount: '',
    method: 'MOBILE_MONEY' as PaymentMethod,
    provider: MOBILE_MONEY_PROVIDERS[0] as string,
    reference: '',
  };

  constructor() {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    try {
      const [insurers, receivables] = await Promise.all([
        firstValueFrom(this.http.get<Insurer[]>(`${this.api}/v1/insurers`)),
        firstValueFrom(this.http.get<InsurerReceivable[]>(`${this.api}/v1/insurance/receivables`)),
      ]);
      this.insurers.set(insurers);
      this.receivables.set(receivables);
      const current = this.selected();
      if (current) {
        this.selected.set(insurers.find((i) => i.id === current.id) ?? null);
      }
    } catch {
      this.status.set('Données tiers payant indisponibles');
    }
  }

  protected async select(insurer: Insurer): Promise<void> {
    this.selected.set(insurer);
    this.editing.set(null);
    this.detail.set(null);
    await this.loadInsurer(insurer);
  }

  private async loadInsurer(insurer: Insurer): Promise<void> {
    const [policies, statements] = await Promise.all([
      firstValueFrom(
        this.http.get<InsurancePolicy[]>(`${this.api}/v1/insurers/${insurer.id}/policies`),
      ),
      firstValueFrom(
        this.http.get<InsurerStatement[]>(`${this.api}/v1/insurers/${insurer.id}/statements`),
      ),
    ]);
    this.policies.set(policies);
    this.statements.set(statements);
    this.policyForm.coverageRate = insurer.details.defaultCoverageRate;
  }

  protected newInsurer(): void {
    this.selected.set(null);
    this.editing.set({
      name: '',
      kind: 'MUTUELLE',
      defaultCoverageRate: 80,
      contactName: null,
      phone: null,
      email: null,
      address: null,
      paymentTermsDays: 30,
    });
  }

  protected editInsurer(insurer: Insurer): void {
    this.editing.set({ ...insurer.details });
  }

  protected async saveInsurer(): Promise<void> {
    const form = this.editing();
    if (!form) {
      return;
    }
    const body = {
      ...form,
      contactName: form.contactName || null,
      phone: form.phone || null,
      email: form.email || null,
      address: form.address || null,
    };
    try {
      const current = this.selected();
      const saved = current
        ? await firstValueFrom(
            this.http.put<Insurer>(`${this.api}/v1/insurers/${current.id}`, body),
          )
        : await firstValueFrom(this.http.post<Insurer>(`${this.api}/v1/insurers`, body));
      this.editing.set(null);
      this.status.set(`${saved.details.name} enregistré (compte ${saved.accountCode})`);
      await this.refresh();
      await this.select(this.insurers().find((i) => i.id === saved.id) ?? saved);
    } catch (error) {
      this.status.set(detailOf(error, 'Enregistrement refusé'));
    }
  }

  protected async toggleActive(insurer: Insurer): Promise<void> {
    try {
      await firstValueFrom(
        this.http.put<Insurer>(`${this.api}/v1/insurers/${insurer.id}/activation`, {
          active: !insurer.active,
        }),
      );
      this.editing.set(null);
      await this.refresh();
    } catch (error) {
      this.status.set(detailOf(error, 'Changement refusé'));
    }
  }

  protected async searchCustomers(term: string): Promise<void> {
    if (term.trim().length < 2) {
      this.customerResults.set([]);
      return;
    }
    this.customerResults.set(
      await firstValueFrom(
        this.http.get<Customer[]>(`${this.api}/v1/customers`, {
          params: { q: term.trim(), limit: 8 },
        }),
      ),
    );
  }

  protected pickCustomer(customer: Customer): void {
    this.policyCustomer.set(customer);
    this.customerTerm = '';
    this.customerResults.set([]);
    if (!this.policyForm.beneficiaryName) {
      this.policyForm.beneficiaryName = customer.displayName;
    }
  }

  protected async savePolicy(insurer: Insurer): Promise<void> {
    const customer = this.policyCustomer();
    if (!customer) {
      this.status.set('Choisissez le client assuré');
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<InsurancePolicy>(`${this.api}/v1/insurers/${insurer.id}/policies`, {
          customerId: customer.id,
          policyNumber: this.policyForm.policyNumber,
          beneficiaryName: this.policyForm.beneficiaryName,
          coverageRate: this.policyForm.coverageRate,
          monthlyCeilingCents: this.policyForm.monthlyCeiling
            ? this.currency.parse(this.policyForm.monthlyCeiling)
            : null,
          validFrom: this.policyForm.validFrom,
          validUntil: this.policyForm.validUntil || null,
        }),
      );
      this.status.set('Police enregistrée');
      this.policyCustomer.set(null);
      this.policyForm = {
        ...this.policyForm,
        policyNumber: '',
        beneficiaryName: '',
        monthlyCeiling: '',
        validUntil: '',
      };
      await this.loadInsurer(insurer);
    } catch (error) {
      this.status.set(detailOf(error, 'Police refusée'));
    }
  }

  protected async generate(insurer: Insurer): Promise<void> {
    try {
      const statement = await firstValueFrom(
        this.http.post<InsurerStatement>(`${this.api}/v1/insurers/${insurer.id}/statements`, {
          periodFrom: this.periodFrom,
          periodTo: this.periodTo,
        }),
      );
      this.status.set(`Bordereau ${statement.number} généré : ${statement.lineCount} ligne(s)`);
      await this.loadInsurer(insurer);
      await this.openStatement(statement);
    } catch (error) {
      this.status.set(detailOf(error, 'Aucun bordereau généré'));
    }
  }

  protected async openStatement(statement: InsurerStatement): Promise<void> {
    this.detail.set(
      await firstValueFrom(
        this.http.get<InsurerStatementDetail>(
          `${this.api}/v1/insurance/statements/${statement.id}`,
        ),
      ),
    );
  }

  protected async issue(statement: InsurerStatement): Promise<void> {
    try {
      await firstValueFrom(
        this.http.post<InsurerStatement>(
          `${this.api}/v1/insurance/statements/${statement.id}/issue`,
          {},
        ),
      );
      this.status.set(`Bordereau ${statement.number} émis`);
      await this.afterStatementChange(statement);
    } catch (error) {
      this.status.set(detailOf(error, 'Émission refusée'));
    }
  }

  protected async pay(statement: InsurerStatement): Promise<void> {
    try {
      const updated = await firstValueFrom(
        this.http.post<InsurerStatement>(
          `${this.api}/v1/insurance/statements/${statement.id}/payment`,
          {
            amountCents: this.currency.parse(this.payment.amount),
            method: this.payment.method,
            provider: this.payment.method === 'MOBILE_MONEY' ? this.payment.provider : null,
            reference: this.payment.reference || null,
          },
        ),
      );
      this.status.set(
        updated.status === 'PAID'
          ? `Bordereau ${updated.number} soldé`
          : `Règlement partiel enregistré`,
      );
      this.payment = { ...this.payment, amount: '', reference: '' };
      await this.afterStatementChange(updated);
    } catch (error) {
      this.status.set(detailOf(error, 'Règlement refusé'));
    }
  }

  private async afterStatementChange(statement: InsurerStatement): Promise<void> {
    const insurer = this.selected();
    if (insurer) {
      await this.loadInsurer(insurer);
    }
    await this.openStatement(statement);
    await this.refresh();
  }

  protected exportUrl(statement: InsurerStatement): string {
    return `${this.api}/v1/insurance/statements/${statement.id}/export`;
  }

  protected statusLabel(status: StatementStatus): string {
    const labels: Record<StatementStatus, string> = {
      DRAFT: 'Brouillon',
      ISSUED: 'Émis',
      PAID: 'Réglé',
    };
    return labels[status];
  }
}

function firstOfMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
}

function detailOf(error: unknown, fallback: string): string {
  return (error as { error?: { detail?: string } }).error?.detail ?? fallback;
}
