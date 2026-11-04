import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PermissionService } from '../../core/auth/permission.service';
import { APP_ENVIRONMENT } from '../../core/config';
import { MoneyPipe } from '../../shared/money.pipe';
import { TerminalLabelPipe } from '../../shared/terminal-label.pipe';
import { Icon } from '../../shared/ui/icon';
import { errorDetail, isoToday } from '../procurement/purchase-orders';

interface SaleSummaryRow {
  id: string;
  ticketNumber: string;
  terminalId: string;
  status: 'COMPLETED' | 'CANCELLED';
  occurredAt: string;
  totalNetCents: number;
  reviewRequired: boolean;
}

/**
 * The sales a seller made over a day. A seller without SALES_VIEW_ALL only ever receives their
 * own from the server; the others can switch between their own and everyone's.
 */
@Component({
  selector: 'app-my-sales-page',
  imports: [DatePipe, FormsModule, MoneyPipe, TerminalLabelPipe, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">{{ mine() ? 'Mes ventes' : 'Ventes de la pharmacie' }}</h1>
          <p class="page-subtitle">
            @if (seesAll()) {
              Les tickets du jour, les vôtres ou ceux de toute l'équipe.
            } @else {
              Les tickets que vous avez encaissés : vous ne voyez que vos propres ventes.
            }
          </p>
        </div>
        <button type="button" class="btn btn-secondary" (click)="load()">
          <app-icon name="refresh" [size]="16" />Actualiser
        </button>
      </header>

      <section class="card mb-5">
        <div class="card-body flex flex-wrap items-end gap-3">
          <label class="block">
            <span class="label">Jour</span>
            <input
              type="date"
              class="input w-40"
              [max]="today"
              [ngModel]="day()"
              (ngModelChange)="day.set($event); load()"
            />
          </label>
          @if (seesAll()) {
            <div class="segmented" role="group" aria-label="Vendeur">
              <button type="button" [class.active]="mine()" (click)="mine.set(true); load()">
                Mes ventes
              </button>
              <button type="button" [class.active]="!mine()" (click)="mine.set(false); load()">
                Toute l'équipe
              </button>
            </div>
          }
          <div class="ml-auto flex gap-6 text-sm">
            <span
              >Tickets <strong class="num">{{ completed().length }}</strong></span
            >
            <span
              >Total <strong class="num">{{ total() | money }}</strong></span
            >
          </div>
        </div>
      </section>

      @if (error(); as message) {
        <p class="alert alert-danger mb-4" role="alert">{{ message }}</p>
      }

      <section class="card overflow-hidden">
        <table class="data-table w-full whitespace-nowrap" data-testid="my-sales">
          <thead>
            <tr>
              <th>Heure</th>
              <th>Ticket</th>
              <th>Poste</th>
              <th>Statut</th>
              <th class="num">Montant</th>
            </tr>
          </thead>
          <tbody>
            @for (sale of sales(); track sale.id) {
              <tr [class.text-subtle]="sale.status === 'CANCELLED'">
                <td>{{ sale.occurredAt | date: 'HH:mm' }}</td>
                <td class="font-mono">{{ sale.ticketNumber }}</td>
                <td>{{ sale.terminalId | terminalLabel }}</td>
                <td>
                  @if (sale.status === 'CANCELLED') {
                    <span class="badge badge-danger">Annulée</span>
                  } @else if (sale.reviewRequired) {
                    <span class="badge badge-warning">En revue</span>
                  } @else {
                    <span class="badge badge-success">Validée</span>
                  }
                </td>
                <td class="num font-medium">{{ sale.totalNetCents | money }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="5" class="py-8 text-center text-muted">Aucune vente ce jour-là.</td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    </div>
  `,
})
export class MySalesPage {
  private readonly http = inject(HttpClient);
  private readonly api = inject(APP_ENVIRONMENT).apiUrl;
  private readonly permissions = inject(PermissionService);

  protected readonly today = isoToday();
  protected readonly day = signal(this.today);
  protected readonly seesAll = computed(() => this.permissions.has('SALES_VIEW_ALL'));
  protected readonly mine = signal(true);
  protected readonly sales = signal<SaleSummaryRow[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly completed = computed(() =>
    this.sales().filter((sale) => sale.status === 'COMPLETED'),
  );
  protected readonly total = computed(() =>
    this.completed().reduce((sum, sale) => sum + sale.totalNetCents, 0),
  );

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    const from = new Date(`${this.day()}T00:00:00`);
    const to = new Date(from.getTime() + 24 * 3600 * 1000);
    try {
      this.error.set(null);
      this.sales.set(
        await firstValueFrom(
          this.http.get<SaleSummaryRow[]>(`${this.api}/v1/sales`, {
            params: {
              from: from.toISOString(),
              to: to.toISOString(),
              limit: 500,
              mine: this.mine() || !this.seesAll(),
            },
          }),
        ),
      );
    } catch (error) {
      this.error.set(errorDetail(error, 'Ventes indisponibles (serveur injoignable)'));
    }
  }
}
