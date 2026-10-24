import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  Customer,
  DrugListing,
  Prescription,
  PrescriptionStatus,
  RegisterEntry,
} from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { APP_ENVIRONMENT } from '../../core/config';
import { CatalogSyncService } from '../../core/offline/catalog-sync.service';
import { CachedProduct } from '../../core/offline/pos-database';

type Tab = 'register' | 'renewals' | 'file';

interface DraftLine {
  productId: string;
  designation: string;
  quantity: number;
  posology: string;
}

/** Dispensing register (ordonnancier, narcotics) and renewable prescriptions kept on file. */
@Component({
  selector: 'app-prescriptions-page',
  imports: [FormsModule, DatePipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <nav class="flex flex-wrap gap-2" role="tablist">
        <button
          type="button"
          class="rounded px-3 py-1"
          [class.bg-brand]="tab() === 'register'"
          [class.text-on-brand]="tab() === 'register'"
          (click)="open('register')"
          *appHasPermission="'PRESCRIPTION_REGISTER_VIEW'"
        >
          Ordonnancier
        </button>
        <button
          type="button"
          class="rounded px-3 py-1"
          [class.bg-brand]="tab() === 'renewals'"
          [class.text-on-brand]="tab() === 'renewals'"
          (click)="open('renewals')"
          *appHasPermission="'PRESCRIPTION_DISPENSE'"
        >
          Renouvellements à rappeler ({{ due().length }})
        </button>
        <button
          type="button"
          class="rounded px-3 py-1"
          [class.bg-brand]="tab() === 'file'"
          [class.text-on-brand]="tab() === 'file'"
          (click)="open('file')"
          *appHasPermission="'PRESCRIPTION_DISPENSE'"
        >
          Ordonnances en dossier
        </button>
      </nav>

      @switch (tab()) {
        @case ('register') {
          <div class="flex flex-wrap items-end gap-2 text-sm">
            <label>Du <input type="date" class="field" [(ngModel)]="from" /></label>
            <label>au <input type="date" class="field" [(ngModel)]="to" /></label>
            <label
              >Liste
              <select class="field" [(ngModel)]="listing">
                <option value="">Tous les produits listés</option>
                <option value="LIST_I">Liste I</option>
                <option value="LIST_II">Liste II</option>
                <option value="STUPEFIANT">Stupéfiants (registre spécial)</option>
              </select></label
            >
            <button type="button" class="rounded border px-3 py-1" (click)="loadRegister()">
              Afficher
            </button>
            <a class="ml-auto rounded border px-3 py-1" [href]="registerExportUrl()" download
              >Exporter (CSV)</a
            >
          </div>
          <table class="w-full rounded border bg-white text-sm">
            <thead class="bg-slate-100 text-left">
              <tr>
                <th class="p-2">N°</th>
                <th class="p-2">Date</th>
                <th class="p-2">Prescripteur</th>
                <th class="p-2">Patient</th>
                <th class="p-2">Produit</th>
                <th class="p-2">Liste</th>
                <th class="p-2 text-right">Qté</th>
                <th class="p-2">Délivré par</th>
              </tr>
            </thead>
            <tbody>
              @for (e of register(); track $index) {
                <tr class="border-t">
                  <td class="p-2 font-mono">{{ e.ordonnancierNumber }}</td>
                  <td class="p-2">{{ e.occurredAt | date: 'dd/MM/yyyy HH:mm' }}</td>
                  <td class="p-2">
                    {{ e.prescriberName }}
                    @if (e.prescriberIdentifier) {
                      <small class="text-slate-500">{{ e.prescriberIdentifier }}</small>
                    }
                  </td>
                  <td class="p-2">{{ e.patientReference ?? e.customerName }}</td>
                  <td class="p-2">
                    {{ e.designation }} <small class="text-slate-500">{{ e.cip13 }}</small>
                  </td>
                  <td class="p-2">
                    <span
                      class="rounded px-1 text-xs"
                      [class.bg-red-100]="e.listing === 'STUPEFIANT'"
                      [class.bg-amber-100]="e.listing !== 'STUPEFIANT'"
                      >{{ e.listing }}</span
                    >
                  </td>
                  <td class="p-2 text-right">{{ e.quantity }}</td>
                  <td class="p-2">{{ e.cashier }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="8" class="p-4 text-center text-slate-500">
                    Aucune délivrance de produit listé sur la période
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
        @case ('renewals') {
          <p class="text-sm text-slate-600">
            Patients à rappeler : la dernière délivrance remonte à 28 jours ou plus, ou l'ordonnance
            expire dans les
            <input
              type="number"
              min="0"
              max="365"
              class="w-16 rounded border px-1"
              [(ngModel)]="withinDays"
            />
            jours.
            <button type="button" class="ml-2 rounded border px-2" (click)="loadDue()">
              Actualiser
            </button>
          </p>
          <ul class="space-y-2">
            @for (p of due(); track p.id) {
              <li class="rounded border bg-white p-3 text-sm">
                <div class="flex flex-wrap justify-between gap-2">
                  <strong>{{ p.customerName ?? p.patientReference ?? 'Patient anonyme' }}</strong>
                  <span>
                    {{ p.renewalsRemaining }} délivrance(s) restante(s) · valable jusqu'au
                    {{ p.validUntil | date: 'dd/MM/yyyy' }}
                    @if (p.nextRenewalDue) {
                      · prochain renouvellement {{ p.nextRenewalDue | date: 'dd/MM' }}
                    }
                  </span>
                </div>
                <p class="text-slate-600">
                  {{ p.prescriberName }} ·
                  @for (l of p.lines; track l.lineNo) {
                    {{ l.designation }} ×{{ l.quantity }}
                    @if (!$last) {
                      ,
                    }
                  }
                </p>
              </li>
            } @empty {
              <li class="text-slate-500">Aucun rappel à faire</li>
            }
          </ul>
        }
        @case ('file') {
          <div class="grid gap-4 lg:grid-cols-[1fr_380px]">
            <section class="space-y-2">
              <div class="flex gap-2 text-sm">
                <input
                  type="search"
                  class="field flex-1"
                  placeholder="Prescripteur ou référence patient"
                  [(ngModel)]="term"
                  (keydown.enter)="loadFile()"
                />
                <select class="field w-40" [(ngModel)]="statusFilter" (ngModelChange)="loadFile()">
                  <option value="">Tous états</option>
                  <option value="ACTIVE">Actives</option>
                  <option value="EXHAUSTED">Épuisées</option>
                  <option value="EXPIRED">Expirées</option>
                  <option value="CANCELLED">Annulées</option>
                </select>
                <button type="button" class="rounded border px-3" (click)="loadFile()">
                  Rechercher
                </button>
              </div>
              <table class="w-full rounded border bg-white text-sm">
                <thead class="bg-slate-100 text-left">
                  <tr>
                    <th class="p-2">Patient</th>
                    <th class="p-2">Prescripteur</th>
                    <th class="p-2">Produits</th>
                    <th class="p-2 text-right">Délivrances</th>
                    <th class="p-2">Validité</th>
                    <th class="p-2">État</th>
                    <th class="p-2"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of file(); track p.id) {
                    <tr class="border-t">
                      <td class="p-2">{{ p.customerName ?? p.patientReference ?? '—' }}</td>
                      <td class="p-2">{{ p.prescriberName }}</td>
                      <td class="p-2">
                        @for (l of p.lines; track l.lineNo) {
                          <div>{{ l.designation }} ×{{ l.quantity }}</div>
                        }
                      </td>
                      <td class="p-2 text-right">{{ p.renewalsUsed }} / {{ p.renewalsAllowed }}</td>
                      <td class="p-2">
                        {{ p.issuedOn | date: 'dd/MM/yy' }} → {{ p.validUntil | date: 'dd/MM/yy' }}
                      </td>
                      <td class="p-2">{{ statusLabel(p.status) }}</td>
                      <td class="p-2">
                        @if (p.status === 'ACTIVE') {
                          <button type="button" class="text-red-700" (click)="cancel(p)">
                            Annuler
                          </button>
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="7" class="p-4 text-center text-slate-500">
                        Aucune ordonnance en dossier
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </section>

            <form class="space-y-2 rounded-lg border bg-white p-4 text-sm" (ngSubmit)="create()">
              <h3 class="font-semibold">Mettre une ordonnance en dossier</h3>
              <div class="relative">
                <input
                  type="search"
                  class="field"
                  placeholder="Client (facultatif)"
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
                @if (draftCustomer(); as c) {
                  <p class="mt-1">
                    Patient : <strong>{{ c.displayName }}</strong>
                    <button
                      type="button"
                      class="ml-2 text-red-700"
                      (click)="draftCustomer.set(null)"
                    >
                      ✕
                    </button>
                  </p>
                }
              </div>
              <input
                class="field"
                placeholder="Prescripteur"
                [(ngModel)]="draft.prescriberName"
                name="prescriber"
                required
                maxlength="150"
              />
              <input
                class="field"
                placeholder="Identifiant du prescripteur"
                [(ngModel)]="draft.prescriberIdentifier"
                name="identifier"
                maxlength="30"
              />
              <input
                class="field"
                placeholder="Référence patient (si pas de fiche client)"
                [(ngModel)]="draft.patientReference"
                name="patient"
                maxlength="64"
              />
              <div class="grid grid-cols-2 gap-2">
                <label
                  >Date de l'ordonnance
                  <input
                    type="date"
                    class="field"
                    [(ngModel)]="draft.issuedOn"
                    name="issued"
                    required
                /></label>
                <label
                  >Valable jusqu'au
                  <input
                    type="date"
                    class="field"
                    [(ngModel)]="draft.validUntil"
                    name="until"
                    required
                /></label>
              </div>
              <label
                >Nombre de délivrances autorisées
                <input
                  type="number"
                  min="1"
                  max="24"
                  class="field"
                  [(ngModel)]="draft.renewalsAllowed"
                  name="allowed"
              /></label>
              <div class="relative">
                <input
                  type="search"
                  class="field"
                  placeholder="Ajouter un produit (nom ou code)"
                  [(ngModel)]="productTerm"
                  (ngModelChange)="searchProducts($event)"
                  name="product"
                />
                @if (productResults().length > 0) {
                  <ul
                    class="absolute z-10 mt-1 max-h-48 w-full overflow-auto rounded border bg-white shadow"
                    role="listbox"
                  >
                    @for (p of productResults(); track p.productId) {
                      <li
                        role="option"
                        class="cursor-pointer px-2 py-1 hover:bg-slate-100"
                        (mousedown)="addProduct(p)"
                      >
                        {{ p.designation }} <small class="text-slate-500">{{ p.cip13 }}</small>
                      </li>
                    }
                  </ul>
                }
              </div>
              @for (line of draftLines(); track line.productId) {
                <div class="flex items-center gap-2">
                  <span class="flex-1">{{ line.designation }}</span>
                  <input
                    type="number"
                    min="1"
                    class="w-16 rounded border px-1"
                    [ngModel]="line.quantity"
                    (ngModelChange)="updateLine(line.productId, { quantity: $event })"
                    [name]="'q' + line.productId"
                  />
                  <input
                    class="w-36 rounded border px-1"
                    placeholder="Posologie"
                    [ngModel]="line.posology"
                    (ngModelChange)="updateLine(line.productId, { posology: $event })"
                    [name]="'p' + line.productId"
                    maxlength="200"
                  />
                  <button
                    type="button"
                    class="text-red-700"
                    (click)="removeLine(line.productId)"
                    aria-label="Retirer"
                  >
                    ✕
                  </button>
                </div>
              }
              <textarea
                class="field"
                rows="2"
                placeholder="Notes"
                [(ngModel)]="draft.notes"
                name="notes"
                maxlength="500"
              ></textarea>
              <button
                type="submit"
                class="w-full rounded bg-brand py-2 text-on-brand"
                [disabled]="draftLines().length === 0"
              >
                Enregistrer en dossier
              </button>
            </form>
          </div>
        }
      }
      @if (status(); as text) {
        <p class="text-sm" role="status">{{ text }}</p>
      }
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
export class PrescriptionsPage {
  private readonly http = inject(HttpClient);
  private readonly catalog = inject(CatalogSyncService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1`;

  protected readonly tab = signal<Tab>('register');
  protected readonly register = signal<RegisterEntry[]>([]);
  protected readonly due = signal<Prescription[]>([]);
  protected readonly file = signal<Prescription[]>([]);
  protected readonly status = signal<string | null>(null);
  protected readonly customerResults = signal<Customer[]>([]);
  protected readonly draftCustomer = signal<Customer | null>(null);
  protected readonly productResults = signal<CachedProduct[]>([]);
  protected readonly draftLines = signal<DraftLine[]>([]);

  protected from = firstOfMonth();
  protected to = new Date().toISOString().slice(0, 10);
  protected listing: DrugListing | '' = '';
  protected withinDays = 7;
  protected term = '';
  protected statusFilter: PrescriptionStatus | '' = '';
  protected customerTerm = '';
  protected productTerm = '';
  protected draft = {
    prescriberName: '',
    prescriberIdentifier: '',
    patientReference: '',
    issuedOn: new Date().toISOString().slice(0, 10),
    validUntil: plusDays(90),
    renewalsAllowed: 3,
    notes: '',
  };

  constructor() {
    void this.open('register');
    void this.loadDue();
  }

  protected async open(tab: Tab): Promise<void> {
    this.tab.set(tab);
    if (tab === 'register') {
      await this.loadRegister();
    } else if (tab === 'renewals') {
      await this.loadDue();
    } else {
      await this.loadFile();
    }
  }

  protected async loadRegister(): Promise<void> {
    try {
      const params: Record<string, string> = { from: this.from, to: this.to };
      if (this.listing) {
        params['listing'] = this.listing;
      }
      this.register.set(
        await firstValueFrom(
          this.http.get<RegisterEntry[]>(`${this.api}/prescriptions/register`, { params }),
        ),
      );
    } catch {
      this.status.set('Registre indisponible');
    }
  }

  protected registerExportUrl(): string {
    const listing = this.listing ? `&listing=${this.listing}` : '';
    return `${this.api}/prescriptions/register/export?from=${this.from}&to=${this.to}${listing}`;
  }

  protected async loadDue(): Promise<void> {
    try {
      this.due.set(
        await firstValueFrom(
          this.http.get<Prescription[]>(`${this.api}/prescriptions/renewals-due`, {
            params: { withinDays: this.withinDays },
          }),
        ),
      );
    } catch {
      // not allowed or offline: the counter is left silent
    }
  }

  protected async loadFile(): Promise<void> {
    const params: Record<string, string> = { limit: '100' };
    if (this.term.trim()) {
      params['q'] = this.term.trim();
    }
    if (this.statusFilter) {
      params['status'] = this.statusFilter;
    }
    this.file.set(
      await firstValueFrom(this.http.get<Prescription[]>(`${this.api}/prescriptions`, { params })),
    );
  }

  protected async searchCustomers(term: string): Promise<void> {
    if (term.trim().length < 2) {
      this.customerResults.set([]);
      return;
    }
    this.customerResults.set(
      await firstValueFrom(
        this.http.get<Customer[]>(`${this.api}/customers`, {
          params: { q: term.trim(), limit: 8 },
        }),
      ),
    );
  }

  protected pickCustomer(customer: Customer): void {
    this.draftCustomer.set(customer);
    this.customerTerm = '';
    this.customerResults.set([]);
  }

  protected async searchProducts(term: string): Promise<void> {
    this.productResults.set(term.trim().length >= 2 ? await this.catalog.search(term) : []);
  }

  protected addProduct(product: CachedProduct): void {
    this.draftLines.update((lines) =>
      lines.some((l) => l.productId === product.productId)
        ? lines
        : [
            ...lines,
            {
              productId: product.productId,
              designation: product.designation,
              quantity: 1,
              posology: '',
            },
          ],
    );
    this.productTerm = '';
    this.productResults.set([]);
  }

  protected updateLine(productId: string, change: Partial<DraftLine>): void {
    this.draftLines.update((lines) =>
      lines.map((l) => (l.productId === productId ? { ...l, ...change } : l)),
    );
  }

  protected removeLine(productId: string): void {
    this.draftLines.update((lines) => lines.filter((l) => l.productId !== productId));
  }

  protected async create(): Promise<void> {
    try {
      const created = await firstValueFrom(
        this.http.post<Prescription>(`${this.api}/prescriptions`, {
          customerId: this.draftCustomer()?.id ?? null,
          prescriberName: this.draft.prescriberName,
          prescriberIdentifier: this.draft.prescriberIdentifier || null,
          patientReference: this.draft.patientReference || null,
          issuedOn: this.draft.issuedOn,
          validUntil: this.draft.validUntil,
          renewalsAllowed: this.draft.renewalsAllowed,
          notes: this.draft.notes || null,
          lines: this.draftLines().map((l) => ({
            productId: l.productId,
            quantity: l.quantity,
            posology: l.posology || null,
          })),
        }),
      );
      this.status.set(`Ordonnance de ${created.prescriberName} enregistrée`);
      this.draftLines.set([]);
      this.draftCustomer.set(null);
      this.draft = {
        ...this.draft,
        prescriberName: '',
        prescriberIdentifier: '',
        patientReference: '',
        notes: '',
      };
      await this.loadFile();
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Enregistrement refusé',
      );
    }
  }

  protected async cancel(prescription: Prescription): Promise<void> {
    const reason = prompt(`Motif d'annulation de l'ordonnance de ${prescription.prescriberName} ?`);
    if (!reason) {
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<Prescription>(`${this.api}/prescriptions/${prescription.id}/cancellation`, {
          reason,
        }),
      );
      await this.loadFile();
      await this.loadDue();
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Annulation refusée',
      );
    }
  }

  protected statusLabel(status: PrescriptionStatus): string {
    const labels: Record<PrescriptionStatus, string> = {
      ACTIVE: 'Active',
      EXHAUSTED: 'Épuisée',
      EXPIRED: 'Expirée',
      CANCELLED: 'Annulée',
    };
    return labels[status];
  }
}

function firstOfMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
}

function plusDays(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}
