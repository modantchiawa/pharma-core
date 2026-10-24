import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
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
  imports: [FormsModule, MoneyPipe, DatePipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-4 p-4 lg:grid-cols-[300px_1fr]">
      <aside class="space-y-3">
        <section class="rounded-lg border bg-white p-3">
          <h2 class="mb-2 font-semibold">Encours tiers payant</h2>
          <table class="w-full text-sm">
            <tbody>
              @for (r of receivables(); track r.insurerId) {
                <tr class="border-t">
                  <td class="py-1">{{ r.insurerName }}</td>
                  <td class="py-1 text-right font-semibold">{{ r.totalCents | money }}</td>
                </tr>
              } @empty {
                <tr>
                  <td class="py-2 text-slate-500">Aucun encours</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
        <section class="rounded-lg border bg-white">
          <h2 class="border-b p-3 font-semibold">Assureurs</h2>
          <ul>
            @for (i of insurers(); track i.id) {
              <li>
                <button
                  type="button"
                  class="flex w-full justify-between px-3 py-2 text-left text-sm hover:bg-slate-50"
                  [class.bg-brand-dark]="selected()?.id === i.id"
                  [class.opacity-50]="!i.active"
                  (click)="select(i)"
                >
                  <span>{{ i.details.name }}</span>
                  <span class="text-slate-500">{{ i.details.defaultCoverageRate }} %</span>
                </button>
              </li>
            } @empty {
              <li class="p-3 text-sm text-slate-500">Aucun assureur</li>
            }
          </ul>
          <button
            type="button"
            class="w-full border-t px-3 py-2 text-left text-sm text-brand"
            *appHasPermission="'INSURANCE_MANAGE'"
            (click)="newInsurer()"
          >
            + Nouvel assureur
          </button>
        </section>
      </aside>

      <section class="space-y-4">
        @if (editing(); as form) {
          <form
            class="grid gap-2 rounded-lg border bg-white p-4 md:grid-cols-2"
            (ngSubmit)="saveInsurer()"
            *appHasPermission="'INSURANCE_MANAGE'"
          >
            <h3 class="font-semibold md:col-span-2">
              {{ selected() ? 'Modifier ' + selected()!.details.name : 'Nouvel assureur' }}
            </h3>
            <input
              class="field"
              placeholder="Nom"
              [(ngModel)]="form.name"
              name="name"
              required
              maxlength="150"
            />
            <select class="field" [(ngModel)]="form.kind" name="kind">
              @for (k of kinds; track k.code) {
                <option [value]="k.code">{{ k.label }}</option>
              }
            </select>
            <label class="text-sm"
              >Taux de couverture par défaut (%)
              <input
                type="number"
                min="0"
                max="100"
                class="field"
                [(ngModel)]="form.defaultCoverageRate"
                name="rate"
            /></label>
            <label class="text-sm"
              >Délai de règlement (jours)
              <input
                type="number"
                min="0"
                class="field"
                [(ngModel)]="form.paymentTermsDays"
                name="terms"
            /></label>
            <input
              class="field"
              placeholder="Contact"
              [(ngModel)]="form.contactName"
              name="contact"
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
              placeholder="Courriel"
              [(ngModel)]="form.email"
              name="email"
              maxlength="200"
            />
            <input
              class="field"
              placeholder="Adresse"
              [(ngModel)]="form.address"
              name="address"
              maxlength="300"
            />
            <div class="flex gap-2 md:col-span-2">
              <button type="submit" class="rounded bg-brand px-3 py-1 text-on-brand">
                Enregistrer
              </button>
              <button type="button" class="rounded border px-3 py-1" (click)="editing.set(null)">
                Annuler
              </button>
              @if (selected(); as i) {
                <button
                  type="button"
                  class="ml-auto rounded border px-3 py-1 text-red-700"
                  (click)="toggleActive(i)"
                >
                  {{ i.active ? 'Désactiver' : 'Réactiver' }}
                </button>
              }
            </div>
          </form>
        }

        @if (selected(); as insurer) {
          <div class="flex flex-wrap items-center gap-2">
            <h2 class="text-lg font-semibold">
              {{ insurer.details.name }}
              <small class="font-normal text-slate-500">
                {{ insurer.code }} · compte {{ insurer.accountCode }}
              </small>
            </h2>
            <nav class="ml-auto flex gap-2" role="tablist">
              <button
                type="button"
                class="rounded px-3 py-1"
                [class.bg-brand]="tab() === 'policies'"
                [class.text-on-brand]="tab() === 'policies'"
                (click)="tab.set('policies')"
              >
                Polices ({{ policies().length }})
              </button>
              <button
                type="button"
                class="rounded px-3 py-1"
                [class.bg-brand]="tab() === 'statements'"
                [class.text-on-brand]="tab() === 'statements'"
                (click)="tab.set('statements')"
              >
                Bordereaux ({{ statements().length }})
              </button>
            </nav>
            <button
              type="button"
              class="rounded border px-3 py-1 text-sm"
              *appHasPermission="'INSURANCE_MANAGE'"
              (click)="editInsurer(insurer)"
            >
              Modifier
            </button>
          </div>

          @switch (tab()) {
            @case ('policies') {
              <table class="w-full rounded border bg-white text-sm">
                <thead class="bg-slate-100 text-left">
                  <tr>
                    <th class="p-2">Police</th>
                    <th class="p-2">Bénéficiaire</th>
                    <th class="p-2 text-right">Taux</th>
                    <th class="p-2 text-right">Plafond mensuel</th>
                    <th class="p-2">Validité</th>
                    <th class="p-2">État</th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of policies(); track p.id) {
                    <tr class="border-t">
                      <td class="p-2">{{ p.policyNumber }}</td>
                      <td class="p-2">{{ p.beneficiaryName }}</td>
                      <td class="p-2 text-right">{{ p.coverageRate }} %</td>
                      <td class="p-2 text-right">
                        {{ p.monthlyCeilingCents === null ? '—' : (p.monthlyCeilingCents | money) }}
                      </td>
                      <td class="p-2">
                        {{ p.validFrom | date: 'dd/MM/yyyy' }}
                        @if (p.validUntil) {
                          → {{ p.validUntil | date: 'dd/MM/yyyy' }}
                        }
                      </td>
                      <td class="p-2">
                        <span
                          class="rounded px-2 py-0.5 text-xs"
                          [class.bg-green-100]="p.usable"
                          [class.bg-slate-200]="!p.usable"
                          >{{
                            p.usable ? 'Active' : p.active ? 'Hors validité' : 'Suspendue'
                          }}</span
                        >
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="6" class="p-4 text-center text-slate-500">Aucune police</td>
                    </tr>
                  }
                </tbody>
              </table>
              <form
                class="grid gap-2 rounded-lg border bg-white p-4 md:grid-cols-3"
                (ngSubmit)="savePolicy(insurer)"
                *appHasPermission="'INSURANCE_MANAGE'"
              >
                <h3 class="font-semibold md:col-span-3">Nouvelle police</h3>
                <div class="relative md:col-span-3">
                  <input
                    type="search"
                    class="field"
                    placeholder="Client (nom, téléphone, code)"
                    [(ngModel)]="customerTerm"
                    (ngModelChange)="searchCustomers($event)"
                    name="customer"
                  />
                  @if (customerResults().length > 0) {
                    <ul
                      class="absolute z-10 mt-1 w-full rounded border bg-white shadow"
                      role="listbox"
                    >
                      @for (c of customerResults(); track c.id) {
                        <li
                          role="option"
                          class="cursor-pointer px-2 py-1 hover:bg-slate-100"
                          (mousedown)="pickCustomer(c)"
                        >
                          {{ c.displayName }} <small class="text-slate-500">{{ c.phone }}</small>
                        </li>
                      }
                    </ul>
                  }
                  @if (policyCustomer(); as c) {
                    <p class="mt-1 text-sm">
                      Assuré : <strong>{{ c.displayName }}</strong> ({{ c.code }})
                    </p>
                  }
                </div>
                <input
                  class="field"
                  placeholder="Numéro de police"
                  [(ngModel)]="policyForm.policyNumber"
                  name="number"
                  required
                  maxlength="60"
                />
                <input
                  class="field"
                  placeholder="Nom du bénéficiaire"
                  [(ngModel)]="policyForm.beneficiaryName"
                  name="beneficiary"
                  required
                  maxlength="150"
                />
                <label class="text-sm"
                  >Taux (%)
                  <input
                    type="number"
                    min="0"
                    max="100"
                    class="field"
                    [(ngModel)]="policyForm.coverageRate"
                    name="prate"
                /></label>
                <label class="text-sm"
                  >Plafond mensuel (vide = illimité)
                  <input
                    inputmode="decimal"
                    class="field"
                    [(ngModel)]="policyForm.monthlyCeiling"
                    name="ceiling"
                /></label>
                <label class="text-sm"
                  >Début
                  <input
                    type="date"
                    class="field"
                    [(ngModel)]="policyForm.validFrom"
                    name="from"
                    required
                /></label>
                <label class="text-sm"
                  >Fin (facultatif)
                  <input type="date" class="field" [(ngModel)]="policyForm.validUntil" name="until"
                /></label>
                <button
                  type="submit"
                  class="rounded bg-brand px-3 py-1 text-on-brand md:col-span-3"
                >
                  Enregistrer la police
                </button>
              </form>
            }
            @case ('statements') {
              <form
                class="flex flex-wrap items-end gap-2 rounded-lg border bg-white p-3 text-sm"
                (ngSubmit)="generate(insurer)"
                *appHasPermission="'INSURANCE_MANAGE'"
              >
                <label
                  >Du
                  <input type="date" class="field" [(ngModel)]="periodFrom" name="pfrom" required
                /></label>
                <label
                  >au <input type="date" class="field" [(ngModel)]="periodTo" name="pto" required
                /></label>
                <button type="submit" class="rounded bg-brand px-3 py-1 text-on-brand">
                  Générer le bordereau
                </button>
              </form>
              <table class="w-full rounded border bg-white text-sm">
                <thead class="bg-slate-100 text-left">
                  <tr>
                    <th class="p-2">Numéro</th>
                    <th class="p-2">Période</th>
                    <th class="p-2 text-right">Lignes</th>
                    <th class="p-2 text-right">Total</th>
                    <th class="p-2 text-right">Réglé</th>
                    <th class="p-2">État</th>
                    <th class="p-2"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (s of statements(); track s.id) {
                    <tr class="border-t" [class.bg-brand-dark]="detail()?.statement.id === s.id">
                      <td class="p-2">
                        <button type="button" class="underline" (click)="openStatement(s)">
                          {{ s.number }}
                        </button>
                      </td>
                      <td class="p-2">
                        {{ s.periodFrom | date: 'dd/MM' }} → {{ s.periodTo | date: 'dd/MM/yyyy' }}
                      </td>
                      <td class="p-2 text-right">{{ s.lineCount }}</td>
                      <td class="p-2 text-right">{{ s.totalCents | money }}</td>
                      <td class="p-2 text-right">{{ s.paidCents | money }}</td>
                      <td class="p-2">{{ statusLabel(s.status) }}</td>
                      <td class="flex gap-1 p-2">
                        <a class="rounded border px-2" [href]="exportUrl(s)" download>CSV</a>
                        @if (s.status === 'DRAFT') {
                          <button
                            type="button"
                            class="rounded border px-2"
                            *appHasPermission="'INSURANCE_MANAGE'"
                            (click)="issue(s)"
                          >
                            Émettre
                          </button>
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="7" class="p-4 text-center text-slate-500">Aucun bordereau</td>
                    </tr>
                  }
                </tbody>
              </table>
              @if (detail(); as d) {
                <section class="rounded-lg border bg-white p-4">
                  <h3 class="mb-2 font-semibold">
                    {{ d.statement.number }} · reste dû {{ d.statement.remainingCents | money }}
                  </h3>
                  <table class="w-full text-sm">
                    <thead class="text-left text-slate-500">
                      <tr>
                        <th class="p-1">Ticket</th>
                        <th class="p-1">Date</th>
                        <th class="p-1">Police</th>
                        <th class="p-1">Bénéficiaire</th>
                        <th class="p-1 text-right">Vente</th>
                        <th class="p-1 text-right">Part assureur</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (l of d.lines; track l.saleId) {
                        <tr class="border-t">
                          <td class="p-1">{{ l.ticketNumber }}</td>
                          <td class="p-1">{{ l.occurredAt | date: 'dd/MM HH:mm' }}</td>
                          <td class="p-1">{{ l.policyNumber }}</td>
                          <td class="p-1">{{ l.beneficiaryName }}</td>
                          <td class="p-1 text-right">{{ l.saleTotalCents | money }}</td>
                          <td class="p-1 text-right">{{ l.amountCents | money }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                  @if (d.statement.status === 'ISSUED') {
                    <form
                      class="mt-3 flex flex-wrap items-end gap-2 text-sm"
                      (ngSubmit)="pay(d.statement)"
                      *appHasPermission="'INSURANCE_MANAGE'"
                    >
                      <label
                        >Montant reçu
                        <input
                          inputmode="decimal"
                          class="field"
                          [(ngModel)]="payment.amount"
                          name="amount"
                          required
                      /></label>
                      <label
                        >Mode
                        <select class="field" [(ngModel)]="payment.method" name="method">
                          <option value="MOBILE_MONEY">Mobile money</option>
                          <option value="CHECK">Chèque / virement</option>
                          <option value="CARD">Carte</option>
                        </select></label
                      >
                      @if (payment.method === 'MOBILE_MONEY') {
                        <label
                          >Opérateur
                          <select class="field" [(ngModel)]="payment.provider" name="provider">
                            @for (p of providers; track p) {
                              <option [value]="p">{{ p.replace('_', ' ') }}</option>
                            }
                          </select></label
                        >
                      }
                      <label
                        >Référence
                        <input
                          class="field"
                          [(ngModel)]="payment.reference"
                          name="ref"
                          maxlength="64"
                      /></label>
                      <button type="submit" class="rounded bg-brand px-3 py-1 text-on-brand">
                        Enregistrer le règlement
                      </button>
                    </form>
                  }
                </section>
              }
            }
          }
        } @else {
          <p class="text-slate-500">Choisissez un assureur ou créez-en un.</p>
        }
        @if (status(); as text) {
          <p class="text-sm" role="status">{{ text }}</p>
        }
      </section>
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
export class InsurancePage {
  private readonly http = inject(HttpClient);
  private readonly currency = inject(CurrencyService);
  private readonly api = inject(APP_ENVIRONMENT).apiUrl;

  protected readonly kinds = KINDS;
  protected readonly providers = MOBILE_MONEY_PROVIDERS;
  protected readonly insurers = signal<Insurer[]>([]);
  protected readonly receivables = signal<InsurerReceivable[]>([]);
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
