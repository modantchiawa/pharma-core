import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ImportJob, ImportKindView, ImportPreview } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { Icon } from '../../shared/ui/icon';

/**
 * Data migration from another program: pick a file (CSV or Excel), check the detected columns,
 * dry-run, then import. The same page serves the editor for the national drug reference
 * (route data `platform: true`).
 */
@Component({
  selector: 'app-import-page',
  imports: [FormsModule, DatePipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">
            {{ platform ? 'Importer le référentiel des médicaments' : 'Importer des données' }}
          </h1>
          <p class="page-subtitle">
            Reprise des données d'un autre logiciel depuis un fichier CSV ou Excel, avec essai à
            blanc avant l'import.
          </p>
        </div>
        <button type="button" class="btn btn-secondary" (click)="template()">
          <app-icon name="download" [size]="16" />
          Modèle CSV
        </button>
      </header>

      <ol class="stepper card mb-6" aria-label="Étapes de l'import">
        @for (s of steps; track s.n) {
          <li
            class="step"
            [class.step-done]="step() > s.n"
            [class.step-current]="step() === s.n"
            [attr.aria-current]="step() === s.n ? 'step' : null"
          >
            <span class="step-badge">
              @if (step() > s.n) {
                <app-icon name="check" [size]="14" [stroke]="2.5" />
              } @else {
                {{ s.n }}
              }
            </span>
            <span class="min-w-0">
              <span class="block text-xs font-semibold md:text-sm">{{ s.title }}</span>
              <span class="hidden text-xs text-muted md:block">{{ s.text }}</span>
            </span>
          </li>
        }
      </ol>

      <div class="space-y-6">
        <section class="card">
          <div class="card-header">
            <h2 class="card-title">Fichier source</h2>
          </div>
          <div class="card-body grid gap-5 lg:grid-cols-[18rem_minmax(0,1fr)]">
            <div>
              <label class="block"
                ><span class="label">Type de données</span>
                <select class="select" [(ngModel)]="kind" name="kind" (ngModelChange)="reset()">
                  @for (k of kinds(); track k.kind) {
                    <option [value]="k.kind">{{ k.label }}</option>
                  }
                </select>
              </label>
              <p class="hint">
                Les colonnes sont reconnues d'après leurs en-têtes ; vous pouvez corriger la
                correspondance avant de lancer l'import.
              </p>
            </div>
            <div class="dropzone" [class.dropzone-filled]="fileName()">
              <span class="dropzone-icon">
                <app-icon [name]="fileName() ? 'spreadsheet' : 'import'" [size]="22" />
              </span>
              @if (fileName(); as name) {
                <span class="block max-w-full truncate text-sm font-semibold">{{ name }}</span>
                <span class="block text-xs text-muted">
                  <span class="font-medium text-brand">Choisir un autre fichier</span> ou le déposer
                  ici
                </span>
              } @else {
                <span class="block text-sm font-medium">
                  <span class="text-brand">Choisir un fichier</span> ou le déposer ici
                </span>
                <span class="block text-xs text-subtle">
                  CSV (séparateur ; , ou tabulation, encodage Windows ou UTF-8) ou Excel (.xlsx)
                </span>
              }
              <input
                type="file"
                aria-label="Fichier"
                accept=".csv,.txt,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                (change)="pick($event)"
              />
            </div>
          </div>
        </section>

        @if (error(); as text) {
          <p class="alert alert-danger" role="alert">
            <app-icon name="error" [size]="18" />
            <span>{{ text }}</span>
          </p>
        }

        @if (preview(); as p) {
          <section class="card overflow-hidden">
            <div class="card-header">
              <div class="min-w-0">
                <h2 class="card-title">Correspondance des colonnes</h2>
                <p class="text-xs text-muted">
                  Aperçu · {{ p.format
                  }}{{ p.delimiter ? ' (séparateur « ' + p.delimiter + ' »)' : '' }} ·
                  {{ p.rows }} ligne(s)
                </p>
              </div>
              @if (p.missingRequired.length === 0) {
                <span class="badge badge-success"
                  ><app-icon name="check" [size]="12" />Colonnes reconnues</span
                >
              }
            </div>
            <div class="card-body space-y-4">
              @if (p.missingRequired.length > 0) {
                <p class="alert alert-warning" role="alert">
                  <app-icon name="alert" [size]="18" />
                  <span>
                    Colonnes obligatoires non reconnues : {{ p.missingRequired.join(', ') }}.
                    Choisissez-les ci-dessous.
                  </span>
                </p>
              }
              <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                @for (f of p.fields; track f.name) {
                  <label class="block">
                    <span class="label">
                      {{ f.label }}
                      @if (f.required) {
                        <span class="text-danger">*</span>
                      }
                    </span>
                    <select
                      class="select"
                      [class.unmapped]="f.column === null"
                      [ngModel]="f.column ?? -1"
                      (ngModelChange)="remap(f.name, $event)"
                      [name]="'map-' + f.name"
                    >
                      <option [value]="-1">— ignorer —</option>
                      @for (h of p.headers; track $index) {
                        <option [value]="$index">{{ h || 'colonne ' + ($index + 1) }}</option>
                      }
                    </select>
                  </label>
                }
              </div>
            </div>

            <div class="overflow-x-auto border-t border-line">
              <table class="data-table sample">
                <thead>
                  <tr>
                    <th class="num">Ligne</th>
                    <th>Contrôle</th>
                    @for (f of p.fields; track f.name) {
                      <th>{{ f.label }}</th>
                    }
                  </tr>
                </thead>
                <tbody>
                  @for (row of p.sample; track row.line) {
                    <tr [class.row-error]="row.error">
                      <td class="num text-subtle">{{ row.line }}</td>
                      <td>
                        @if (row.error) {
                          <span class="inline-flex items-center gap-1.5 font-medium text-danger">
                            <app-icon name="error" [size]="14" />{{ row.error }}
                          </span>
                        } @else {
                          <span class="inline-flex items-center gap-1.5 text-success">
                            <app-icon name="checkCircle" [size]="14" />OK
                          </span>
                        }
                      </td>
                      @for (f of p.fields; track f.name) {
                        <td>{{ row.values[f.name] }}</td>
                      }
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            <div class="card-footer">
              <div class="flex flex-1 flex-wrap items-end gap-4">
                @if (!platform) {
                  <label class="flex min-h-[var(--control-h)] items-center gap-3 text-sm">
                    <input
                      type="checkbox"
                      role="switch"
                      class="toggle"
                      [(ngModel)]="updateExisting"
                      name="update"
                    />
                    Mettre à jour les fiches déjà présentes
                  </label>
                }
                @if (kind === 'STOCK') {
                  <label class="block"
                    ><span class="label">Lot par défaut</span
                    ><input class="input w-40" [(ngModel)]="defaultLot" name="lot"
                  /></label>
                  <label class="block"
                    ><span class="label">Péremption par défaut</span
                    ><input
                      type="date"
                      class="input w-44"
                      [(ngModel)]="defaultExpiry"
                      name="expiry"
                  /></label>
                }
                @if (kind === 'PRODUCTS' || kind === 'STOCK' || platform) {
                  <label class="block"
                    ><span class="label">TVA par défaut (%)</span
                    ><input
                      inputmode="decimal"
                      class="input w-28"
                      [(ngModel)]="defaultVatRate"
                      name="vat"
                  /></label>
                }
              </div>
              <div class="flex gap-2">
                <button
                  type="button"
                  class="btn btn-secondary"
                  (click)="run(true)"
                  [disabled]="busy() || p.missingRequired.length > 0"
                >
                  <app-icon name="eye" [size]="16" />
                  Essai à blanc
                </button>
                <button
                  type="button"
                  class="btn btn-primary"
                  (click)="run(false)"
                  [disabled]="busy() || p.missingRequired.length > 0"
                >
                  <app-icon [name]="busy() ? 'refresh' : 'import'" [size]="16" />
                  {{ busy() ? 'Import en cours…' : 'Importer' }}
                </button>
              </div>
            </div>
          </section>
        }

        @if (result(); as job) {
          <section class="card" aria-live="polite">
            <div class="card-header">
              <div class="min-w-0">
                <h2 class="card-title">
                  {{ job.dryRun ? 'Essai à blanc' : 'Import' }} · {{ job.fileName }}
                </h2>
                <p class="text-xs text-muted">{{ job.total }} ligne(s) traitée(s)</p>
              </div>
              @if (job.dryRun) {
                <span class="badge badge-info">Aucune donnée enregistrée</span>
              } @else {
                <span class="badge badge-success"><span class="dot"></span>Terminé</span>
              }
            </div>
            <div class="card-body space-y-4">
              <div class="grid gap-3 sm:grid-cols-3">
                <div class="stat">
                  <p class="stat-label">Créées</p>
                  <p class="stat-value text-success">{{ job.created }}</p>
                </div>
                <div class="stat">
                  <p class="stat-label">Mises à jour</p>
                  <p class="stat-value text-info">{{ job.updated }}</p>
                </div>
                <div class="stat">
                  <p class="stat-label">Rejetées</p>
                  <p class="stat-value" [class.text-danger]="job.rejected > 0">
                    {{ job.rejected }}
                  </p>
                </div>
              </div>
              @if (job.errors.length > 0) {
                <ul
                  class="max-h-60 divide-y divide-[var(--border)] overflow-auto rounded-[var(--radius)] border border-line text-sm"
                >
                  @for (e of job.errors; track e.line) {
                    <li class="flex gap-3 px-3 py-2">
                      <span class="badge badge-danger shrink-0">Ligne {{ e.line }}</span>
                      <span class="min-w-0">{{ e.message }}</span>
                    </li>
                  }
                </ul>
              }
            </div>
          </section>
        }

        <section class="card overflow-hidden">
          <div class="card-header">
            <h2 class="card-title flex items-center gap-2">
              <app-icon name="clock" [size]="16" class="text-muted" />
              Historique
            </h2>
          </div>
          @if (history().length > 0) {
            <div class="overflow-x-auto">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Type</th>
                    <th>Fichier</th>
                    <th class="num">Créées</th>
                    <th class="num">Mises à jour</th>
                    <th class="num">Rejetées</th>
                    <th>Par</th>
                  </tr>
                </thead>
                <tbody>
                  @for (j of history(); track j.id) {
                    <tr [class.text-muted]="j.dryRun">
                      <td class="whitespace-nowrap">
                        {{ j.startedAt | date: 'dd/MM/yyyy HH:mm' }}
                        @if (j.dryRun) {
                          <span class="badge ml-1">essai</span>
                        }
                      </td>
                      <td>{{ label(j.kind) }}</td>
                      <td class="max-w-64 truncate" [attr.title]="j.fileName">{{ j.fileName }}</td>
                      <td class="num">{{ j.created }}</td>
                      <td class="num">{{ j.updated }}</td>
                      <td class="num" [class.text-danger]="j.rejected > 0">{{ j.rejected }}</td>
                      <td>{{ j.startedBy }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <div class="empty-state">
              <app-icon name="spreadsheet" [size]="22" />
              <p>Aucun import pour le moment. Commencez par un essai à blanc.</p>
            </div>
          }
        </section>
      </div>
    </div>
  `,
  styles: `
    .stepper {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 0.25rem;
      padding: 0.5rem;
    }
    .step {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.4rem;
      padding: 0.6rem 0.25rem;
      border-radius: var(--radius);
      text-align: center;
      color: var(--text-muted);
    }
    @media (min-width: 768px) {
      .step {
        flex-direction: row;
        gap: 0.75rem;
        padding: 0.75rem 1rem;
        text-align: left;
      }
    }
    .step-current {
      background: var(--brand-softer);
      color: var(--text);
    }
    .step-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.9rem;
      height: 1.9rem;
      flex-shrink: 0;
      border: 1.5px solid var(--border-strong);
      border-radius: 999px;
      background: var(--surface);
      font-size: 0.8rem;
      font-weight: 650;
      color: var(--text-muted);
    }
    .step-current .step-badge {
      border-color: var(--brand-primary);
      color: var(--brand-text);
      box-shadow: 0 0 0 4px color-mix(in oklab, var(--brand-primary) 14%, transparent);
    }
    .step-done .step-badge {
      border-color: var(--brand-primary);
      background: var(--brand-primary);
      color: var(--brand-on-primary);
    }
    .dropzone {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 0.35rem;
      min-height: 9.5rem;
      padding: 1.25rem;
      border: 1.5px dashed var(--border-strong);
      border-radius: var(--radius-lg);
      background: var(--surface-2);
      text-align: center;
      transition:
        border-color 120ms ease,
        background-color 120ms ease;
    }
    .dropzone:hover,
    .dropzone:has(input:focus-visible) {
      border-color: var(--brand-primary);
      background: var(--brand-softer);
    }
    .dropzone-filled {
      border-style: solid;
      border-color: color-mix(in oklab, var(--brand-primary) 45%, var(--border));
      background: var(--brand-softer);
    }
    .dropzone input {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      opacity: 0;
      cursor: pointer;
    }
    .dropzone-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 3rem;
      height: 3rem;
      margin-bottom: 0.35rem;
      border: 1px solid var(--border);
      border-radius: 0.85rem;
      background: var(--surface);
      color: var(--brand-text);
      box-shadow: var(--shadow-sm);
    }
    .unmapped {
      color: var(--text-subtle);
    }
    .sample {
      font-size: 0.8125rem;
    }
    .sample th,
    .sample td {
      white-space: nowrap;
    }
    .sample tbody tr.row-error,
    .sample tbody tr.row-error:hover {
      background: var(--danger-soft);
    }
    .card-footer {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: 1rem;
      padding: calc(1rem * var(--density)) calc(1.25rem * var(--density));
      border-top: 1px solid var(--border);
      background: var(--surface-2);
    }
    .stat {
      padding: 0.85rem 1rem;
      border: 1px solid var(--border);
      border-radius: var(--radius);
      background: var(--surface-2);
    }
    .stat-label {
      font-size: 0.75rem;
      font-weight: 550;
      color: var(--text-muted);
    }
    .stat-value {
      margin-top: 0.25rem;
      font-size: 1.5rem;
      font-weight: 700;
      letter-spacing: -0.02em;
      font-variant-numeric: tabular-nums;
    }
    .toggle {
      appearance: none;
      position: relative;
      flex-shrink: 0;
      width: 2.25rem !important;
      height: 1.3rem !important;
      border-radius: 999px;
      background: var(--border-strong);
      transition: background-color 150ms ease;
      cursor: pointer;
    }
    .toggle::after {
      content: '';
      position: absolute;
      top: 0.15rem;
      left: 0.15rem;
      width: 1rem;
      height: 1rem;
      border-radius: 999px;
      background: #fff;
      box-shadow: 0 1px 2px rgb(0 0 0 / 0.25);
      transition: transform 150ms ease;
    }
    .toggle:checked {
      background: var(--brand-primary);
    }
    .toggle:checked::after {
      transform: translateX(0.95rem);
    }
  `,
})
export class ImportPage {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  protected readonly platform = inject(ActivatedRoute).snapshot.data['platform'] === true;
  protected readonly kinds = signal<ImportKindView[]>([]);
  protected readonly preview = signal<ImportPreview | null>(null);
  protected readonly result = signal<ImportJob | null>(null);
  protected readonly history = signal<ImportJob[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly fileName = signal<string | null>(null);
  protected readonly steps = [
    { n: 1, title: 'Fichier', text: 'CSV ou Excel' },
    { n: 2, title: 'Colonnes', text: 'Vérifier la correspondance' },
    { n: 3, title: 'Essai à blanc', text: 'Contrôle sans enregistrer' },
    { n: 4, title: 'Import', text: 'Enregistrement des données' },
  ];
  /** Current step of the stepper: 5 once a real import has run. */
  protected readonly step = computed(() => {
    const job = this.result();
    if (job) {
      return job.dryRun ? 4 : 5;
    }
    return this.preview() ? 2 : 1;
  });
  protected kind = this.platform ? 'CATALOG' : 'PRODUCTS';
  protected updateExisting = false;
  protected defaultLot = 'MIGRATION';
  protected defaultExpiry = '';
  protected defaultVatRate = '0';
  private file: File | null = null;
  private mapping: Record<string, number> = {};
  protected readonly base = computed(() =>
    this.platform ? `${this.env.apiUrl}/platform/catalog/imports` : `${this.env.apiUrl}/v1/imports`,
  );

  constructor() {
    void this.load();
  }

  protected label(kind: string): string {
    return this.kinds().find((k) => k.kind === kind)?.label ?? kind;
  }

  protected reset(): void {
    this.preview.set(null);
    this.result.set(null);
    this.mapping = {};
    if (this.file) {
      void this.analyse();
    }
  }

  protected pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file = input.files?.[0] ?? null;
    this.fileName.set(this.file?.name ?? null);
    this.mapping = {};
    this.result.set(null);
    if (this.file) {
      void this.analyse();
    }
  }

  protected remap(field: string, column: number | string): void {
    this.mapping = { ...this.mapping, [field]: Number(column) };
    void this.analyse();
  }

  protected template(): void {
    const url = this.platform ? `${this.base()}/template` : `${this.base()}/templates/${this.kind}`;
    void firstValueFrom(this.http.get(url, { responseType: 'blob' })).then((blob) => {
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = `modele-${this.kind.toLowerCase()}.csv`;
      link.click();
      URL.revokeObjectURL(link.href);
    });
  }

  protected async run(dryRun: boolean): Promise<void> {
    if (!this.file) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    const form = this.form();
    form.append('dryRun', String(dryRun));
    form.append('updateExisting', String(this.platform ? true : this.updateExisting));
    if (this.kind === 'STOCK') {
      form.append('defaultLot', this.defaultLot);
      if (this.defaultExpiry) {
        form.append('defaultExpiry', this.defaultExpiry);
      }
    }
    if (this.defaultVatRate.trim()) {
      form.append('defaultVatRate', this.defaultVatRate.replace(',', '.'));
    }
    try {
      this.result.set(await firstValueFrom(this.http.post<ImportJob>(this.base(), form)));
      await this.loadHistory();
    } catch (error) {
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Import impossible',
      );
    } finally {
      this.busy.set(false);
    }
  }

  private async analyse(): Promise<void> {
    this.error.set(null);
    try {
      this.preview.set(
        await firstValueFrom(this.http.post<ImportPreview>(`${this.base()}/preview`, this.form())),
      );
    } catch (error) {
      this.preview.set(null);
      this.error.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Fichier illisible',
      );
    }
  }

  private form(): FormData {
    const form = new FormData();
    form.append('file', this.file as File, this.file?.name ?? 'import');
    if (!this.platform) {
      form.append('kind', this.kind);
    }
    if (Object.keys(this.mapping).length > 0) {
      form.append('mapping', JSON.stringify(this.mapping));
    }
    return form;
  }

  private async load(): Promise<void> {
    try {
      this.kinds.set(await firstValueFrom(this.http.get<ImportKindView[]>(`${this.base()}/kinds`)));
      await this.loadHistory();
    } catch {
      this.error.set('Importation indisponible');
    }
  }

  private async loadHistory(): Promise<void> {
    this.history.set(await firstValueFrom(this.http.get<ImportJob[]>(this.base())));
  }
}
