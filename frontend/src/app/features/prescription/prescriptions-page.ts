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
import { Icon } from '../../shared/ui/icon';

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
  imports: [FormsModule, DatePipe, HasPermissionDirective, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Ordonnances</h1>
          <p class="page-subtitle">
            Ordonnancier des produits listés, renouvellements à rappeler et ordonnances en dossier.
          </p>
        </div>
      </header>

      <div class="mb-5 max-w-full overflow-x-auto">
        <nav class="segmented [&>button]:whitespace-nowrap" role="tablist">
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="tab() === 'register'"
            (click)="open('register')"
            *appHasPermission="'PRESCRIPTION_REGISTER_VIEW'"
          >
            Ordonnancier
          </button>
          <button
            type="button"
            role="tab"
            class="inline-flex items-center gap-2"
            [attr.aria-selected]="tab() === 'renewals'"
            (click)="open('renewals')"
            *appHasPermission="'PRESCRIPTION_DISPENSE'"
          >
            Renouvellements à rappeler
            <span class="badge" [class.badge-warning]="due().length > 0">{{ due().length }}</span>
          </button>
          <button
            type="button"
            role="tab"
            [attr.aria-selected]="tab() === 'file'"
            (click)="open('file')"
            *appHasPermission="'PRESCRIPTION_DISPENSE'"
          >
            Ordonnances en dossier
          </button>
        </nav>
      </div>

      @if (status(); as text) {
        <div class="alert alert-info mb-4" role="status">
          <app-icon name="info" [size]="18" />
          <span>{{ text }}</span>
        </div>
      }

      @switch (tab()) {
        @case ('register') {
          <div class="card">
            <div class="card-header flex-wrap items-end">
              <div class="grid w-full grid-cols-2 items-end gap-3 sm:flex sm:w-auto sm:flex-wrap">
                <label class="block">
                  <span class="label">Du</span>
                  <input type="date" class="input" [(ngModel)]="from" />
                </label>
                <label class="block">
                  <span class="label">au</span>
                  <input type="date" class="input" [(ngModel)]="to" />
                </label>
                <label class="col-span-2 block">
                  <span class="label">Liste</span>
                  <select class="select sm:w-64" [(ngModel)]="listing">
                    <option value="">Tous les produits listés</option>
                    <option value="LIST_I">Liste I</option>
                    <option value="LIST_II">Liste II</option>
                    <option value="STUPEFIANT">Stupéfiants (registre spécial)</option>
                  </select>
                </label>
                <button type="button" class="btn btn-secondary col-span-2" (click)="loadRegister()">
                  <app-icon name="search" [size]="16" />
                  Afficher
                </button>
              </div>
              <a class="btn btn-ghost" [href]="registerExportUrl()" download>
                <app-icon name="download" [size]="16" />
                Exporter (CSV)
              </a>
            </div>
            @if (register().length > 0) {
              <div class="overflow-x-auto">
                <table class="data-table whitespace-nowrap">
                  <thead>
                    <tr>
                      <th>N°</th>
                      <th>Date</th>
                      <th>Prescripteur</th>
                      <th>Patient</th>
                      <th>Produit</th>
                      <th>Liste</th>
                      <th class="text-right">Qté</th>
                      <th>Délivré par</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (e of register(); track $index) {
                      <tr>
                        <td class="font-mono text-xs">{{ e.ordonnancierNumber }}</td>
                        <td class="whitespace-nowrap tabular-nums text-muted">
                          {{ e.occurredAt | date: 'dd/MM/yyyy HH:mm' }}
                        </td>
                        <td>
                          <p class="font-medium">{{ e.prescriberName }}</p>
                          @if (e.prescriberIdentifier) {
                            <p class="text-xs text-subtle">{{ e.prescriberIdentifier }}</p>
                          }
                        </td>
                        <td>{{ e.patientReference ?? e.customerName }}</td>
                        <td>
                          <p>{{ e.designation }}</p>
                          <p class="font-mono text-xs text-subtle">{{ e.cip13 }}</p>
                        </td>
                        <td>
                          <span
                            class="badge"
                            [class.badge-danger]="e.listing === 'STUPEFIANT'"
                            [class.badge-warning]="e.listing !== 'STUPEFIANT'"
                            >{{ e.listing }}</span
                          >
                        </td>
                        <td class="num font-medium">{{ e.quantity }}</td>
                        <td class="text-muted">{{ e.cashier }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <div class="empty-state">
                <app-icon name="prescription" [size]="22" />
                <p>Aucune délivrance de produit listé sur la période</p>
              </div>
            }
          </div>
        }
        @case ('renewals') {
          <div class="card">
            <div class="card-header flex-wrap">
              <p class="flex flex-wrap items-center gap-x-1.5 gap-y-2 text-sm text-muted">
                Patients à rappeler : la dernière délivrance remonte à 28 jours ou plus, ou
                l'ordonnance expire dans les
                <input
                  type="number"
                  min="0"
                  max="365"
                  class="input !inline-block !min-h-8 !w-20 text-right tabular-nums"
                  [(ngModel)]="withinDays"
                />
                jours.
              </p>
              <button type="button" class="btn btn-secondary btn-sm" (click)="loadDue()">
                <app-icon name="refresh" [size]="16" />
                Actualiser
              </button>
            </div>
            <ul class="divide-y divide-[var(--border)]">
              @for (p of due(); track p.id) {
                <li class="flex flex-col gap-2 px-5 py-4 text-sm sm:flex-row sm:items-start">
                  <span
                    class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[var(--warning-soft)] text-warning"
                  >
                    <app-icon name="bell" [size]="16" />
                  </span>
                  <div class="min-w-0 flex-1">
                    <div class="flex flex-wrap items-center justify-between gap-2">
                      <strong class="font-semibold">{{
                        p.customerName ?? p.patientReference ?? 'Patient anonyme'
                      }}</strong>
                      <span class="badge badge-warning">
                        {{ p.renewalsRemaining }} délivrance(s) restante(s)
                      </span>
                    </div>
                    <p class="mt-1 text-muted">
                      {{ p.prescriberName }} ·
                      @for (l of p.lines; track l.lineNo) {
                        {{ l.designation }} ×{{ l.quantity }}
                        @if (!$last) {
                          ,
                        }
                      }
                    </p>
                    <p class="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-subtle">
                      <app-icon name="calendar" [size]="13" />
                      valable jusqu'au {{ p.validUntil | date: 'dd/MM/yyyy' }}
                      @if (p.nextRenewalDue) {
                        · prochain renouvellement {{ p.nextRenewalDue | date: 'dd/MM' }}
                      }
                    </p>
                  </div>
                </li>
              } @empty {
                <li class="empty-state">
                  <app-icon name="checkCircle" [size]="22" />
                  <p>Aucun rappel à faire</p>
                </li>
              }
            </ul>
          </div>
        }
        @case ('file') {
          <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
            <section class="card min-w-0 self-start">
              <div class="card-header flex-wrap">
                <label class="input-with-icon min-w-0 flex-1 basis-56">
                  <app-icon name="search" [size]="16" />
                  <input
                    type="search"
                    class="input"
                    placeholder="Prescripteur ou référence patient"
                    [(ngModel)]="term"
                    (keydown.enter)="loadFile()"
                  />
                </label>
                <div class="flex gap-2">
                  <select
                    class="select !w-40"
                    [(ngModel)]="statusFilter"
                    (ngModelChange)="loadFile()"
                  >
                    <option value="">Tous états</option>
                    <option value="ACTIVE">Actives</option>
                    <option value="EXHAUSTED">Épuisées</option>
                    <option value="EXPIRED">Expirées</option>
                    <option value="CANCELLED">Annulées</option>
                  </select>
                  <button type="button" class="btn btn-secondary" (click)="loadFile()">
                    Rechercher
                  </button>
                </div>
              </div>
              @if (file().length > 0) {
                <div class="overflow-x-auto">
                  <table class="data-table whitespace-nowrap">
                    <thead>
                      <tr>
                        <th>Patient</th>
                        <th>Prescripteur</th>
                        <th>Produits</th>
                        <th class="text-right">Délivrances</th>
                        <th>Validité</th>
                        <th>État</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (p of file(); track p.id) {
                        <tr>
                          <td class="font-medium">
                            {{ p.customerName ?? p.patientReference ?? '—' }}
                          </td>
                          <td>{{ p.prescriberName }}</td>
                          <td>
                            @for (l of p.lines; track l.lineNo) {
                              <div class="whitespace-nowrap">
                                {{ l.designation }}
                                <span class="text-subtle">×{{ l.quantity }}</span>
                              </div>
                            }
                          </td>
                          <td class="num tabular-nums">
                            {{ p.renewalsUsed }} / {{ p.renewalsAllowed }}
                          </td>
                          <td class="whitespace-nowrap tabular-nums text-muted">
                            {{ p.issuedOn | date: 'dd/MM/yy' }} →
                            {{ p.validUntil | date: 'dd/MM/yy' }}
                          </td>
                          <td>
                            <span
                              class="badge"
                              [class.badge-success]="p.status === 'ACTIVE'"
                              [class.badge-warning]="p.status === 'EXPIRED'"
                              [class.badge-danger]="p.status === 'CANCELLED'"
                              ><span class="dot"></span>{{ statusLabel(p.status) }}</span
                            >
                          </td>
                          <td class="text-right">
                            @if (p.status === 'ACTIVE') {
                              <button
                                type="button"
                                class="btn btn-ghost btn-sm text-danger"
                                (click)="cancel(p)"
                              >
                                Annuler
                              </button>
                            }
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <div class="empty-state">
                  <app-icon name="prescription" [size]="22" />
                  <p>Aucune ordonnance en dossier</p>
                </div>
              }
            </section>

            <form class="card self-start" (ngSubmit)="create()">
              <div class="card-header">
                <h3 class="card-title">Mettre une ordonnance en dossier</h3>
                <app-icon name="prescription" [size]="18" class="text-subtle" />
              </div>
              <div class="card-body space-y-3 text-sm">
                <div class="relative">
                  <span class="label">Patient</span>
                  <label class="input-with-icon block">
                    <app-icon name="search" [size]="16" />
                    <input
                      type="search"
                      class="input"
                      placeholder="Client (facultatif)"
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
                          class="flex cursor-pointer items-center justify-between gap-2 rounded-[var(--radius-sm)] px-3 py-2 hover:bg-surface-3"
                          (mousedown)="pickCustomer(c)"
                        >
                          <span class="font-medium">{{ c.displayName }}</span>
                          <small class="text-subtle">{{ c.phone }}</small>
                        </li>
                      }
                    </ul>
                  }
                  @if (draftCustomer(); as c) {
                    <div
                      class="mt-2 flex items-center gap-2 rounded-[var(--radius-sm)] bg-brand-soft px-3 py-2"
                    >
                      <app-icon name="user" [size]="16" />
                      <span class="min-w-0 flex-1 truncate">
                        Patient : <strong>{{ c.displayName }}</strong>
                      </span>
                      <button
                        type="button"
                        class="btn btn-ghost btn-icon btn-sm"
                        (click)="draftCustomer.set(null)"
                        aria-label="Retirer le patient"
                      >
                        <app-icon name="x" [size]="16" />
                      </button>
                    </div>
                  }
                </div>
                <label class="block">
                  <span class="label">Prescripteur</span>
                  <input
                    class="input"
                    placeholder="Prescripteur"
                    [(ngModel)]="draft.prescriberName"
                    name="prescriber"
                    required
                    maxlength="150"
                  />
                </label>
                <div class="space-y-3">
                  <input
                    class="input"
                    placeholder="Identifiant du prescripteur"
                    [(ngModel)]="draft.prescriberIdentifier"
                    name="identifier"
                    maxlength="30"
                  />
                  <input
                    class="input"
                    placeholder="Référence patient (si pas de fiche client)"
                    [(ngModel)]="draft.patientReference"
                    name="patient"
                    maxlength="64"
                  />
                </div>
                <div class="grid grid-cols-2 gap-3">
                  <label class="block">
                    <span class="label">Date de l'ordonnance</span>
                    <input
                      type="date"
                      class="input"
                      [(ngModel)]="draft.issuedOn"
                      name="issued"
                      required
                    />
                  </label>
                  <label class="block">
                    <span class="label">Valable jusqu'au</span>
                    <input
                      type="date"
                      class="input"
                      [(ngModel)]="draft.validUntil"
                      name="until"
                      required
                    />
                  </label>
                </div>
                <label class="block">
                  <span class="label">Nombre de délivrances autorisées</span>
                  <input
                    type="number"
                    min="1"
                    max="24"
                    class="input"
                    [(ngModel)]="draft.renewalsAllowed"
                    name="allowed"
                  />
                </label>
                <div class="relative">
                  <span class="label">Produits</span>
                  <label class="input-with-icon block">
                    <app-icon name="pill" [size]="16" />
                    <input
                      type="search"
                      class="input"
                      placeholder="Ajouter un produit (nom ou code)"
                      [(ngModel)]="productTerm"
                      (ngModelChange)="searchProducts($event)"
                      name="product"
                    />
                  </label>
                  @if (productResults().length > 0) {
                    <ul
                      class="card absolute z-10 mt-1 max-h-48 w-full overflow-auto p-1 shadow-[var(--shadow-lg)]"
                      role="listbox"
                    >
                      @for (p of productResults(); track p.productId) {
                        <li
                          role="option"
                          class="flex cursor-pointer items-center justify-between gap-2 rounded-[var(--radius-sm)] px-3 py-2 hover:bg-surface-3"
                          (mousedown)="addProduct(p)"
                        >
                          <span class="min-w-0 truncate font-medium">{{ p.designation }}</span>
                          <small class="font-mono text-subtle">{{ p.cip13 }}</small>
                        </li>
                      }
                    </ul>
                  }
                </div>
                @if (draftLines().length > 0) {
                  <ul
                    class="divide-y divide-[var(--border)] rounded-[var(--radius)] border border-line"
                  >
                    @for (line of draftLines(); track line.productId) {
                      <li class="flex flex-wrap items-center gap-2 px-3 py-2">
                        <span
                          class="min-w-0 flex-1 basis-full truncate font-medium sm:basis-auto"
                          >{{ line.designation }}</span
                        >
                        <input
                          type="number"
                          min="1"
                          class="input !min-h-8 !w-16 text-right tabular-nums"
                          aria-label="Quantité"
                          [ngModel]="line.quantity"
                          (ngModelChange)="updateLine(line.productId, { quantity: $event })"
                          [name]="'q' + line.productId"
                        />
                        <input
                          class="input !min-h-8 min-w-0 flex-1 sm:!w-36 sm:flex-none"
                          placeholder="Posologie"
                          [ngModel]="line.posology"
                          (ngModelChange)="updateLine(line.productId, { posology: $event })"
                          [name]="'p' + line.productId"
                          maxlength="200"
                        />
                        <button
                          type="button"
                          class="btn btn-ghost btn-icon btn-sm text-danger"
                          (click)="removeLine(line.productId)"
                          aria-label="Retirer"
                        >
                          <app-icon name="trash" [size]="16" />
                        </button>
                      </li>
                    }
                  </ul>
                }
                <textarea
                  class="textarea"
                  rows="2"
                  placeholder="Notes"
                  [(ngModel)]="draft.notes"
                  name="notes"
                  maxlength="500"
                ></textarea>
                <button
                  type="submit"
                  class="btn btn-primary w-full"
                  [disabled]="draftLines().length === 0"
                >
                  <app-icon name="check" [size]="16" />
                  Enregistrer en dossier
                </button>
              </div>
            </form>
          </div>
        }
      }
    </div>
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
