import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { StockCount, StockCountLine } from '../../core/api/models';
import { HasPermissionDirective } from '../../core/auth/has-permission.directive';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';
import { parseGs1 } from '../../shared/gs1';

/**
 * Blind physical inventory: the counter scans or picks a lot and types what is on the shelf;
 * expected quantities and variances appear only to the pharmacist who validates.
 */
@Component({
  selector: 'app-stock-count-page',
  imports: [FormsModule, DatePipe, MoneyPipe, HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-4 p-4 lg:grid-cols-[1fr_340px]">
      <section class="space-y-3">
        @if (count(); as c) {
          <div class="rounded-lg border bg-white p-4">
            <div class="flex items-start justify-between gap-2">
              <div>
                <h2 class="text-lg font-semibold">{{ c.label }}</h2>
                <p class="text-sm text-slate-600">
                  {{
                    c.status === 'OPEN'
                      ? 'En cours'
                      : c.status === 'VALIDATED'
                        ? 'Validé'
                        : 'Annulé'
                  }}
                  · démarré le {{ c.startedAt | date: 'dd/MM HH:mm' }}
                  @if (c.location) {
                    · emplacement {{ c.location }}
                  }
                  · {{ c.countedLines }} / {{ c.lineCount }} lots comptés
                </p>
                @if (c.varianceUnits !== undefined) {
                  <p class="text-sm" [class.text-red-700]="(c.varianceUnits ?? 0) < 0">
                    Écart : {{ c.varianceUnits }} unité(s),
                    {{ c.varianceValueCents ?? 0 | money }} au coût
                  </p>
                }
              </div>
              @if (c.status === 'OPEN') {
                <div class="flex gap-2" *appHasPermission="'STOCK_COUNT_VALIDATE'">
                  <button
                    type="button"
                    class="rounded bg-brand px-3 py-1 text-on-brand"
                    (click)="validate(c)"
                  >
                    Valider et régulariser
                  </button>
                  <button type="button" class="rounded border px-3 py-1" (click)="cancel(c)">
                    Annuler
                  </button>
                </div>
              }
            </div>
            @if (c.status === 'OPEN') {
              <form class="mt-3 flex gap-2" (ngSubmit)="scan(c)">
                <input
                  class="flex-1 rounded border px-3 py-2"
                  placeholder="Scanner le code (DataMatrix GS1 ou EAN) ou taper le lot"
                  [(ngModel)]="scanInput"
                  name="scan"
                  autofocus
                />
                <input
                  type="number"
                  min="0"
                  class="w-24 rounded border px-3 py-2"
                  placeholder="Qté"
                  [(ngModel)]="scanQuantity"
                  name="scanQuantity"
                />
                <button type="submit" class="rounded border px-3">Compter</button>
              </form>
            }
            <input
              type="search"
              class="mt-3 w-full rounded border px-3 py-2"
              placeholder="Filtrer les lignes (produit, lot)"
              [(ngModel)]="filter"
              name="filter"
              (ngModelChange)="filterSignal.set($event)"
            />
            <table class="mt-2 w-full text-sm">
              <thead class="bg-slate-100 text-left">
                <tr>
                  <th class="p-2">Produit</th>
                  <th class="p-2">Lot</th>
                  <th class="p-2">Péremption</th>
                  @if (reveal()) {
                    <th class="p-2 text-right">Attendu</th>
                  }
                  <th class="p-2 text-right">Compté</th>
                  @if (reveal()) {
                    <th class="p-2 text-right">Écart</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (l of visibleLines(); track l.id) {
                  <tr
                    class="border-t"
                    [class.bg-amber-50]="reveal() && l.variance !== undefined && l.variance !== 0"
                    [class.text-slate-400]="
                      l.countedQuantity === null || l.countedQuantity === undefined
                    "
                  >
                    <td class="p-2">
                      {{ l.designation }} <small class="text-slate-500">{{ l.cip13 }}</small>
                    </td>
                    <td class="p-2">{{ l.lotNumber }}</td>
                    <td class="p-2">{{ l.expiryDate | date: 'MM/yyyy' }}</td>
                    @if (reveal()) {
                      <td class="p-2 text-right">{{ l.expectedQuantity }}</td>
                    }
                    <td class="p-2 text-right">
                      @if (c.status === 'OPEN') {
                        <input
                          type="number"
                          min="0"
                          class="w-20 rounded border px-2 py-1 text-right"
                          [ngModel]="l.countedQuantity"
                          (ngModelChange)="draft[l.id] = $event"
                          (blur)="countLine(c, l)"
                          (keydown.enter)="countLine(c, l)"
                          [name]="'q-' + l.id"
                        />
                      } @else {
                        {{ l.countedQuantity ?? '—' }}
                      }
                    </td>
                    @if (reveal()) {
                      <td class="p-2 text-right font-semibold">{{ l.variance ?? '' }}</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <p class="rounded-lg border bg-white p-4 text-slate-600">
            Sélectionnez un inventaire ou démarrez-en un nouveau.
          </p>
        }
        @if (status(); as text) {
          <p class="text-sm" role="status">{{ text }}</p>
        }
      </section>

      <aside class="space-y-4">
        <form class="space-y-2 rounded-lg border bg-white p-4" (ngSubmit)="start()">
          <h3 class="font-semibold">Nouvel inventaire</h3>
          <input
            class="field"
            placeholder="Libellé (ex. Rayon A, octobre)"
            [(ngModel)]="label"
            name="label"
            required
            maxlength="150"
          />
          <input
            class="field"
            placeholder="Emplacement (optionnel)"
            [(ngModel)]="location"
            name="location"
            maxlength="50"
          />
          <p class="text-xs text-slate-500">
            Les quantités attendues sont figées au démarrage et cachées au compteur.
          </p>
          <button type="submit" class="w-full rounded bg-brand py-2 text-on-brand">Démarrer</button>
        </form>
        <div class="rounded-lg border bg-white p-4">
          <h3 class="mb-2 font-semibold">Inventaires récents</h3>
          <ul class="text-sm">
            @for (c of recent(); track c.id) {
              <li
                class="flex cursor-pointer justify-between border-t py-1 hover:bg-slate-50"
                (click)="open(c.id)"
              >
                <span>{{ c.label }}</span>
                <span class="text-slate-500"
                  >{{ c.status }} · {{ c.countedLines }}/{{ c.lineCount }}</span
                >
              </li>
            } @empty {
              <li class="text-slate-500">Aucun inventaire</li>
            }
          </ul>
        </div>
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
export class StockCountPage {
  private readonly http = inject(HttpClient);
  private readonly permissions = inject(PermissionService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/inventory/counts`;

  protected readonly count = signal<StockCount | null>(null);
  protected readonly recent = signal<StockCount[]>([]);
  protected readonly status = signal<string | null>(null);
  protected readonly filterSignal = signal('');
  protected readonly reveal = computed(
    () => this.permissions.has('STOCK_COUNT_VALIDATE') || this.count()?.status !== 'OPEN',
  );
  protected readonly visibleLines = computed(() => {
    const term = this.filterSignal().trim().toLowerCase();
    const lines = this.count()?.lines ?? [];
    return term
      ? lines.filter(
          (l) =>
            l.designation.toLowerCase().includes(term) ||
            l.lotNumber.toLowerCase().includes(term) ||
            l.cip13.includes(term),
        )
      : lines;
  });
  protected readonly draft: Record<string, number | null> = {};
  protected label = '';
  protected location = '';
  protected filter = '';
  protected scanInput = '';
  protected scanQuantity: number | null = null;

  constructor() {
    void this.loadRecent();
  }

  protected async start(): Promise<void> {
    await this.run(() =>
      firstValueFrom(
        this.http.post<StockCount>(this.api, {
          label: this.label,
          location: this.location || null,
        }),
      ),
    );
    this.label = '';
    this.location = '';
    await this.loadRecent();
  }

  protected async open(id: string): Promise<void> {
    await this.run(() => firstValueFrom(this.http.get<StockCount>(`${this.api}/${id}`)));
  }

  protected async countLine(c: StockCount, line: StockCountLine): Promise<void> {
    const quantity = this.draft[line.id];
    if (quantity === undefined || quantity === null || quantity === line.countedQuantity) {
      return;
    }
    await this.run(() =>
      firstValueFrom(
        this.http.post<StockCount>(`${this.api}/${c.id}/lines/${line.id}`, {
          quantity: Number(quantity),
        }),
      ),
    );
  }

  /** A GS1 code carries product and lot; a plain lot number is matched against the lines. */
  protected async scan(c: StockCount): Promise<void> {
    const raw = this.scanInput.trim();
    if (!raw || this.scanQuantity === null) {
      this.status.set('Indiquez la quantité comptée');
      return;
    }
    const gs1 = parseGs1(raw);
    let line: StockCountLine | undefined;
    if (gs1?.ean13 && gs1.lot) {
      line = c.lines.find(
        (l) =>
          (l.ean13 === gs1.ean13 || l.cip13 === gs1.ean13) &&
          l.lotNumber.toLowerCase() === gs1.lot!.toLowerCase(),
      );
    } else {
      const matches = c.lines.filter(
        (l) =>
          l.lotNumber.toLowerCase() === raw.toLowerCase() || l.ean13 === raw || l.cip13 === raw,
      );
      line = matches.length === 1 ? matches[0] : undefined;
      if (matches.length > 1) {
        this.status.set('Plusieurs lots correspondent : précisez via la liste');
        return;
      }
    }
    if (!line) {
      this.status.set(`Aucun lot de l'inventaire ne correspond à « ${raw} »`);
      return;
    }
    this.draft[line.id] = this.scanQuantity;
    await this.countLine(c, line);
    this.scanInput = '';
    this.scanQuantity = null;
  }

  protected async validate(c: StockCount): Promise<void> {
    const notes = prompt('Observations (optionnel)') ?? '';
    await this.run(() =>
      firstValueFrom(
        this.http.post<StockCount>(`${this.api}/${c.id}/validation`, { notes: notes || null }),
      ),
    );
    await this.loadRecent();
  }

  protected async cancel(c: StockCount): Promise<void> {
    const reason = prompt("Motif d'annulation");
    if (!reason) {
      return;
    }
    await this.run(() =>
      firstValueFrom(this.http.post<StockCount>(`${this.api}/${c.id}/cancellation`, { reason })),
    );
    await this.loadRecent();
  }

  private async run(action: () => Promise<StockCount>): Promise<void> {
    this.status.set(null);
    try {
      const result = await action();
      this.count.set(result);
      for (const line of result.lines) {
        this.draft[line.id] = line.countedQuantity ?? null;
      }
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Opération refusée',
      );
    }
  }

  private async loadRecent(): Promise<void> {
    try {
      this.recent.set(
        await firstValueFrom(this.http.get<StockCount[]>(this.api, { params: { limit: 20 } })),
      );
    } catch {
      this.recent.set([]);
    }
  }
}
