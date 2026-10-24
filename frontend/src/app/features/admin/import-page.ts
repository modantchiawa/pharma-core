import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ImportJob, ImportKindView, ImportPreview } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';

/**
 * Data migration from another program: pick a file (CSV or Excel), check the detected columns,
 * dry-run, then import. The same page serves the editor for the national drug reference
 * (route data `platform: true`).
 */
@Component({
  selector: 'app-import-page',
  imports: [FormsModule, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4 p-4">
      <section class="rounded-lg border bg-white p-4">
        <h2 class="mb-2 text-lg font-semibold">
          {{ platform ? 'Importer le référentiel des médicaments' : 'Importer des données' }}
        </h2>
        <p class="mb-3 text-sm text-slate-600">
          Fichier CSV (séparateur ; , ou tabulation, encodage Windows ou UTF-8) ou Excel (.xlsx).
          Les colonnes sont reconnues d'après leurs en-têtes ; vous pouvez corriger la
          correspondance avant de lancer l'import. Commencez par un essai à blanc.
        </p>
        <div class="grid gap-3 md:grid-cols-[1fr_2fr_auto]">
          <label class="text-sm"
            >Type de données
            <select class="field" [(ngModel)]="kind" name="kind" (ngModelChange)="reset()">
              @for (k of kinds(); track k.kind) {
                <option [value]="k.kind">{{ k.label }}</option>
              }
            </select>
          </label>
          <label class="text-sm"
            >Fichier
            <input
              type="file"
              class="field"
              accept=".csv,.txt,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              (change)="pick($event)"
            />
          </label>
          <div class="flex items-end gap-2">
            <button type="button" class="rounded border px-3 py-2" (click)="template()">
              Modèle CSV
            </button>
          </div>
        </div>
      </section>

      @if (preview(); as p) {
        <section class="rounded-lg border bg-white p-4">
          <h3 class="font-semibold">
            Aperçu · {{ p.format
            }}{{ p.delimiter ? ' (séparateur « ' + p.delimiter + ' »)' : '' }} ·
            {{ p.rows }} ligne(s)
          </h3>
          @if (p.missingRequired.length > 0) {
            <p class="mt-2 rounded bg-amber-100 px-3 py-2 text-sm text-amber-900" role="alert">
              Colonnes obligatoires non reconnues : {{ p.missingRequired.join(', ') }}.
              Choisissez-les ci-dessous.
            </p>
          }
          <div class="mt-3 grid gap-2 md:grid-cols-3">
            @for (f of p.fields; track f.name) {
              <label class="text-sm">
                {{ f.label }}
                @if (f.required) {
                  <span class="text-red-700">*</span>
                }
                <select
                  class="field"
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

          <div class="mt-3 overflow-auto">
            <table class="w-full text-xs">
              <thead class="bg-slate-100 text-left">
                <tr>
                  <th class="p-1">Ligne</th>
                  @for (f of p.fields; track f.name) {
                    <th class="p-1">{{ f.label }}</th>
                  }
                  <th class="p-1">Contrôle</th>
                </tr>
              </thead>
              <tbody>
                @for (row of p.sample; track row.line) {
                  <tr class="border-t" [class.bg-red-50]="row.error">
                    <td class="p-1">{{ row.line }}</td>
                    @for (f of p.fields; track f.name) {
                      <td class="p-1">{{ row.values[f.name] }}</td>
                    }
                    <td class="p-1 text-red-700">{{ row.error ?? '' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <div class="mt-4 flex flex-wrap items-end gap-3">
            @if (!platform) {
              <label class="flex items-center gap-2 text-sm">
                <input type="checkbox" [(ngModel)]="updateExisting" name="update" />
                Mettre à jour les fiches déjà présentes
              </label>
            }
            @if (kind === 'STOCK') {
              <label class="text-sm"
                >Lot par défaut<input class="field" [(ngModel)]="defaultLot" name="lot"
              /></label>
              <label class="text-sm"
                >Péremption par défaut<input
                  type="date"
                  class="field"
                  [(ngModel)]="defaultExpiry"
                  name="expiry"
              /></label>
            }
            @if (kind === 'PRODUCTS' || kind === 'STOCK' || platform) {
              <label class="text-sm"
                >TVA par défaut (%)<input
                  inputmode="decimal"
                  class="field w-24"
                  [(ngModel)]="defaultVatRate"
                  name="vat"
              /></label>
            }
            <button
              type="button"
              class="rounded border px-3 py-2"
              (click)="run(true)"
              [disabled]="busy() || p.missingRequired.length > 0"
            >
              Essai à blanc
            </button>
            <button
              type="button"
              class="rounded bg-brand px-3 py-2 text-on-brand"
              (click)="run(false)"
              [disabled]="busy() || p.missingRequired.length > 0"
            >
              {{ busy() ? 'Import en cours…' : 'Importer' }}
            </button>
          </div>
        </section>
      }

      @if (result(); as job) {
        <section class="rounded-lg border bg-white p-4" aria-live="polite">
          <h3 class="font-semibold">
            {{ job.dryRun ? 'Essai à blanc' : 'Import' }} · {{ job.fileName }}
          </h3>
          <p class="mt-1 text-sm">
            {{ job.total }} ligne(s) : <strong>{{ job.created }}</strong> créée(s),
            <strong>{{ job.updated }}</strong> mise(s) à jour,
            <strong [class.text-red-700]="job.rejected > 0">{{ job.rejected }}</strong> rejetée(s).
          </p>
          @if (job.errors.length > 0) {
            <ul class="mt-2 max-h-60 list-inside list-disc overflow-auto text-sm text-red-800">
              @for (e of job.errors; track e.line) {
                <li>Ligne {{ e.line }} : {{ e.message }}</li>
              }
            </ul>
          }
        </section>
      }
      @if (error(); as text) {
        <p class="rounded bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">{{ text }}</p>
      }

      @if (history().length > 0) {
        <section class="rounded-lg border bg-white p-4">
          <h3 class="mb-2 font-semibold">Historique</h3>
          <table class="w-full text-sm">
            <thead class="text-left text-slate-500">
              <tr>
                <th>Date</th>
                <th>Type</th>
                <th>Fichier</th>
                <th class="text-right">Créées</th>
                <th class="text-right">Mises à jour</th>
                <th class="text-right">Rejetées</th>
                <th>Par</th>
              </tr>
            </thead>
            <tbody>
              @for (j of history(); track j.id) {
                <tr class="border-t" [class.text-slate-400]="j.dryRun">
                  <td>
                    {{ j.startedAt | date: 'dd/MM/yyyy HH:mm' }}{{ j.dryRun ? ' (essai)' : '' }}
                  </td>
                  <td>{{ label(j.kind) }}</td>
                  <td>{{ j.fileName }}</td>
                  <td class="text-right">{{ j.created }}</td>
                  <td class="text-right">{{ j.updated }}</td>
                  <td class="text-right">{{ j.rejected }}</td>
                  <td>{{ j.startedBy }}</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
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
      background: white;
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
