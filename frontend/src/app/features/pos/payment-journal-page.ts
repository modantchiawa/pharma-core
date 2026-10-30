import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PaymentCategoryTotal, PaymentJournal, PaymentJournalEntry } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { ToastService } from '../../core/ui/toast.service';
import { MoneyPipe } from '../../shared/money.pipe';
import { Icon } from '../../shared/ui/icon';
import { errorDetail, isoToday } from '../procurement/purchase-orders';
import {
  categoryIcon,
  categoryKey,
  categoryLabel,
  CategoryRef,
  entriesTotal,
  inCategory,
  matchesEntry,
  parseCategoryKey,
  shiftDate,
} from './payment-journal';

const STATUS_BADGES: Record<
  PaymentJournalEntry['confirmationStatus'],
  { label: string; badge: string } | null
> = {
  NOT_REQUIRED: null,
  PENDING: { label: 'À pointer', badge: 'badge-warning' },
  CONFIRMED: { label: 'Pointé', badge: 'badge-success' },
  REJECTED: { label: 'Rejeté', badge: 'badge-danger' },
};

/**
 * What each "caisse" took over a day: cash, MyNITA, Airtel Money, card, credit… with its total,
 * its number of transactions and the transactions themselves, for the whole pharmacy or one
 * checkout station. Query parameters: {@code date=YYYY-MM-DD}, {@code category=MOBILE_MONEY:MYNITA}.
 */
@Component({
  selector: 'app-payment-journal-page',
  imports: [FormsModule, DatePipe, MoneyPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Encaissements par caisse</h1>
          <p class="page-subtitle">
            Le total et les transactions de chaque mode de paiement : espèces, MyNITA, Airtel Money,
            carte…
          </p>
        </div>
        <div class="flex gap-2 print:hidden">
          <button type="button" class="btn btn-secondary" (click)="print()">
            <app-icon name="printer" [size]="16" />
            Imprimer
          </button>
          <button type="button" class="btn btn-secondary" (click)="load()">
            <app-icon name="refresh" [size]="16" />
            Actualiser
          </button>
        </div>
      </header>

      <section class="card mb-5 print:hidden" aria-label="Période et poste">
        <div class="card-body flex flex-wrap items-end gap-3">
          <div class="flex items-end gap-1">
            <button
              type="button"
              class="btn btn-ghost btn-icon"
              aria-label="Jour précédent"
              (click)="shiftDay(-1)"
            >
              <app-icon name="chevronLeft" [size]="16" />
            </button>
            <label class="block">
              <span class="label">Du</span>
              <input
                type="date"
                class="input w-40"
                [max]="today"
                [ngModel]="from()"
                (ngModelChange)="setFrom($event)"
                data-testid="journal-from"
              />
            </label>
            <label class="block">
              <span class="label">Au</span>
              <input
                type="date"
                class="input w-40"
                [min]="from()"
                [max]="today"
                [ngModel]="to()"
                (ngModelChange)="setTo($event)"
                data-testid="journal-to"
              />
            </label>
            <button
              type="button"
              class="btn btn-ghost btn-icon"
              aria-label="Jour suivant"
              [disabled]="to() >= today"
              (click)="shiftDay(1)"
            >
              <app-icon name="chevronRight" [size]="16" />
            </button>
          </div>
          <div class="segmented" role="group" aria-label="Raccourcis">
            <button
              type="button"
              [attr.aria-selected]="from() === today && to() === today"
              (click)="setDay(today)"
            >
              Aujourd'hui
            </button>
            <button
              type="button"
              [attr.aria-selected]="from() === yesterday && to() === yesterday"
              (click)="setDay(yesterday)"
            >
              Hier
            </button>
            <button
              type="button"
              [attr.aria-selected]="from() === weekStart && to() === today"
              (click)="setPeriod(weekStart, today)"
            >
              7 jours
            </button>
          </div>
          <label class="block w-full sm:w-auto">
            <span class="label">Poste (caisse)</span>
            <select
              class="select sm:w-52"
              [ngModel]="terminal()"
              (ngModelChange)="terminal.set($event); load()"
              data-testid="journal-terminal"
            >
              <option value="">Tous les postes</option>
              @for (t of journal()?.terminals ?? []; track t) {
                <option [value]="t">{{ t }}</option>
              }
            </select>
          </label>
        </div>
      </section>

      <p class="mb-3 hidden text-sm print:block">
        {{ periodLabel() }} · {{ terminal() || 'Tous les postes' }}
      </p>

      <div
        class="mb-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-4"
        role="list"
        aria-label="Catégories"
      >
        <button
          type="button"
          role="listitem"
          class="card kpi text-left transition-colors"
          [class.ring-2]="selected() === null"
          [class.ring-[var(--brand)]]="selected() === null"
          (click)="select(null)"
          data-testid="category-all"
        >
          <div class="kpi-label">
            <span class="kpi-icon"><app-icon name="banknote" [size]="16" /></span>
            Toutes les caisses
          </div>
          <div class="kpi-value">{{ journal()?.totalCents ?? 0 | money }}</div>
          <div class="kpi-foot">
            {{ journal()?.count ?? 0 }} transaction(s) · {{ periodLabel() }}
          </div>
        </button>
        @for (c of journal()?.categories ?? []; track key(c)) {
          <button
            type="button"
            role="listitem"
            class="card kpi text-left transition-colors"
            [class.ring-2]="isSelected(c)"
            [class.ring-[var(--brand)]]="isSelected(c)"
            (click)="select(c)"
            [attr.data-testid]="'category-' + key(c)"
          >
            <div class="kpi-label">
              <span class="kpi-icon"><app-icon [name]="icon(c)" [size]="16" /></span>
              {{ label(c) }}
              @if (c.provider && c.method === 'MOBILE_MONEY') {
                <span class="text-xs text-subtle">mobile money</span>
              }
            </div>
            <div class="kpi-value">{{ c.totalCents | money }}</div>
            <div class="kpi-foot">
              {{ c.count }} transaction(s)
              @if (c.pendingCents > 0) {
                · <span class="text-warning">{{ c.pendingCents | money }} à pointer</span>
              }
              @if (c.rejectedCount > 0) {
                · <span class="text-danger">{{ c.rejectedCount }} rejetée(s)</span>
              }
            </div>
          </button>
        }
      </div>

      <section class="card" data-testid="journal-entries">
        <div class="card-header flex-wrap">
          <div class="flex items-center gap-2">
            <h2 class="card-title">
              {{ selected() ? label(selected()!) : 'Toutes les transactions' }}
            </h2>
            <span class="badge">{{ shown().length }}</span>
          </div>
          <label class="relative w-full sm:w-72 print:hidden">
            <span class="sr-only">Rechercher une transaction</span>
            <app-icon
              name="search"
              [size]="16"
              class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-subtle"
            />
            <input
              type="search"
              class="input w-full pl-9"
              placeholder="Ticket, référence, caissier"
              [ngModel]="search()"
              (ngModelChange)="search.set($event)"
            />
          </label>
        </div>
        @if (shown().length > 0) {
          <div class="overflow-x-auto">
            <table class="data-table whitespace-nowrap">
              <thead>
                <tr>
                  <th>Heure</th>
                  <th>Ticket</th>
                  <th>Poste</th>
                  <th>Caissier</th>
                  <th>Mode</th>
                  <th>Référence</th>
                  <th class="text-right">Montant</th>
                  <th>Relevé</th>
                </tr>
              </thead>
              <tbody>
                @for (e of shown(); track e.paymentId) {
                  <tr [class.opacity-60]="e.confirmationStatus === 'REJECTED'">
                    <td class="tabular-nums text-muted">
                      {{ e.occurredAt | date: (multiDay() ? 'dd/MM HH:mm' : 'HH:mm') }}
                    </td>
                    <td class="font-medium">{{ e.ticketNumber }}</td>
                    <td>{{ e.terminalId }}</td>
                    <td>{{ e.cashierName ?? '—' }}</td>
                    <td>
                      <span class="inline-flex items-center gap-1.5">
                        <app-icon [name]="icon(e)" [size]="15" class="text-subtle" />
                        {{ label(e) }}
                      </span>
                    </td>
                    <td class="font-mono text-xs">{{ e.reference ?? '—' }}</td>
                    <td
                      class="num font-medium"
                      [class.line-through]="e.confirmationStatus === 'REJECTED'"
                    >
                      {{ e.amountCents | money }}
                    </td>
                    <td>
                      @if (statusBadge(e); as s) {
                        <span class="badge" [class]="s.badge">{{ s.label }}</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr class="font-semibold">
                  <td colspan="6">
                    Total · {{ shownTotal().count }} transaction(s)
                    @if (search().trim()) {
                      <span class="text-xs font-normal text-muted">(filtrées)</span>
                    }
                  </td>
                  <td class="num" data-testid="journal-total">
                    {{ shownTotal().totalCents | money }}
                  </td>
                  <td></td>
                </tr>
              </tfoot>
            </table>
          </div>
          @if (entries().length >= limit) {
            <p class="border-t px-5 py-2 text-xs text-muted">
              Les {{ limit }} transactions les plus récentes sont affichées : réduisez la période
              pour tout voir. Les totaux des caisses ci-dessus restent complets.
            </p>
          }
        } @else {
          <div class="empty-state">
            <app-icon name="banknote" [size]="22" />
            <p>{{ loading() ? 'Chargement…' : 'Aucun encaissement sur la période' }}</p>
          </div>
        }
      </section>
    </div>
  `,
})
export class PaymentJournalPage {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/payments/journal`;
  private readonly toast = inject(ToastService);

  /** Query parameters (router input binding). */
  readonly date = input<string>();
  readonly category = input<string>();

  protected readonly limit = 2000;
  protected readonly today = isoToday();
  protected readonly yesterday = shiftDate(this.today, -1);
  protected readonly weekStart = shiftDate(this.today, -6);
  protected readonly from = linkedSignal(() => this.date() ?? this.today);
  protected readonly to = linkedSignal(() => this.date() ?? this.today);
  protected readonly terminal = signal('');
  protected readonly selected = linkedSignal<CategoryRef | null>(() =>
    parseCategoryKey(this.category()),
  );
  protected readonly search = signal('');
  protected readonly journal = signal<PaymentJournal | null>(null);
  protected readonly entries = signal<PaymentJournalEntry[]>([]);
  protected readonly loading = signal(false);
  protected readonly multiDay = computed(() => this.from() !== this.to());
  protected readonly shown = computed(() =>
    this.entries().filter((e) => inCategory(e, this.selected()) && matchesEntry(e, this.search())),
  );
  protected readonly shownTotal = computed(() => entriesTotal(this.shown()));
  protected readonly periodLabel = computed(() => {
    const day = (iso: string) => iso.split('-').reverse().join('/');
    return this.multiDay()
      ? `du ${day(this.from())} au ${day(this.to())}`
      : `le ${day(this.from())}`;
  });

  constructor() {
    void this.load();
  }

  protected key(category: CategoryRef): string {
    return categoryKey(category);
  }

  protected label(category: CategoryRef): string {
    return categoryLabel(category);
  }

  protected icon(category: CategoryRef) {
    return categoryIcon(category);
  }

  protected statusBadge(entry: PaymentJournalEntry) {
    return STATUS_BADGES[entry.confirmationStatus];
  }

  protected isSelected(category: PaymentCategoryTotal): boolean {
    const current = this.selected();
    return current !== null && categoryKey(current) === categoryKey(category);
  }

  protected select(category: CategoryRef | null): void {
    this.selected.set(category && { method: category.method, provider: category.provider });
  }

  protected setDay(day: string): void {
    this.setPeriod(day, day);
  }

  protected setPeriod(from: string, to: string): void {
    this.from.set(from);
    this.to.set(to);
    void this.load();
  }

  protected setFrom(value: string): void {
    if (!value) {
      return;
    }
    // a single day stays a single day; the end never precedes the start
    const to = this.multiDay() && this.to() >= value ? this.to() : value;
    this.setPeriod(value, to);
  }

  protected setTo(value: string): void {
    if (!value) {
      return;
    }
    this.setPeriod(value < this.from() ? value : this.from(), value);
  }

  /** Previous or next day; a period moves by its own length. */
  protected shiftDay(direction: 1 | -1): void {
    const span = Math.round((Date.parse(this.to()) - Date.parse(this.from())) / 86_400_000 + 1);
    const step = direction * span;
    const to = shiftDate(this.to(), step);
    this.setPeriod(shiftDate(this.from(), step), to > this.today ? this.today : to);
  }

  protected print(): void {
    window.print();
  }

  protected async load(): Promise<void> {
    const params: Record<string, string> = { from: this.from(), to: this.to() };
    if (this.terminal()) {
      params['terminalId'] = this.terminal();
    }
    this.loading.set(true);
    try {
      const [journal, entries] = await Promise.all([
        firstValueFrom(this.http.get<PaymentJournal>(this.api, { params })),
        firstValueFrom(
          this.http.get<PaymentJournalEntry[]>(`${this.api}/entries`, {
            params: { ...params, limit: this.limit },
          }),
        ),
      ]);
      this.journal.set(journal);
      this.entries.set(entries);
    } catch (error) {
      this.journal.set(null);
      this.entries.set([]);
      this.toast.error(errorDetail(error, 'Encaissements indisponibles'));
    } finally {
      this.loading.set(false);
    }
  }
}
