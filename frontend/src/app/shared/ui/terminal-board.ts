import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { TerminalBoard, TerminalSyncState, TerminalView } from '../../core/api/models';
import {
  SYNC_STATES,
  SyncBadge,
  badgeClass,
  forgettable,
  observedLabel,
  plural,
  relativeTime,
  terminalBadge,
} from '../terminal-sync';
import { Icon } from './icon';

type Filter = TerminalSyncState | 'ALL';

/**
 * Cash registers of one pharmacy with their synchronization state, shared by the vendor console
 * and the pharmacy's "Serveur local" page. Presentational: the page loads the board, ticks `now`
 * for the relative times and performs the forget actions it emits.
 *
 *   <app-terminal-board [board]="board" [now]="now()" [canForget]="true" (forget)="…" (forgetStale)="…" />
 *
 * Each register shows the name the pharmacy gave it above its unique id; with `canRename`, a
 * "Nommer" action edits the name and place in place and emits `rename`.
 */
@Component({
  selector: 'app-terminal-board',
  imports: [DatePipe, FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card overflow-hidden" aria-labelledby="terminal-board-title">
      <div class="card-header flex-wrap">
        <div class="min-w-0">
          <h2 id="terminal-board-title" class="card-title flex items-center gap-2">
            <app-icon name="monitor" [size]="16" class="text-muted" />
            Postes de caisse
            <span class="badge">{{ board().counts.total }}</span>
          </h2>
          <p class="mt-0.5 text-xs text-muted">
            {{ observed() }} · hors ligne après {{ staleMinutes() }} min sans signal
          </p>
        </div>
        @if (canForget() && forgettableCount() > 0) {
          <button
            type="button"
            class="btn btn-ghost btn-sm"
            [disabled]="busy()"
            (click)="forgetStale.emit()"
            data-testid="forget-stale"
          >
            <app-icon name="trash" [size]="14" />
            Oublier {{ plural(forgettableCount(), 'poste hors ligne', 'postes hors ligne') }}
          </button>
        }
      </div>

      @if (board().relayedByHub) {
        <div class="alert alert-info m-4 mb-0 items-center text-sm" role="note">
          <app-icon name="server" [size]="16" />
          <span>
            Les postes s'adressent au serveur local de l'officine, qui transmet leur état au cloud :
            état connu {{ observedAgo() }}.
          </span>
        </div>
      }

      @if (board().terminals.length > 0) {
        <div class="overflow-x-auto px-4 pt-3 pb-1 sm:px-5">
          <div class="segmented" role="tablist" aria-label="Filtrer les postes par état">
            <button
              type="button"
              role="tab"
              [attr.aria-selected]="activeFilter() === 'ALL'"
              (click)="filter.set('ALL')"
            >
              Tous <span class="count">{{ board().counts.total }}</span>
            </button>
            @for (s of states; track s.state) {
              @if (countOf(s.state) > 0) {
                <button
                  type="button"
                  role="tab"
                  [attr.aria-selected]="activeFilter() === s.state"
                  (click)="filter.set(s.state)"
                >
                  {{ s.label }} <span class="count">{{ countOf(s.state) }}</span>
                </button>
              }
            }
          </div>
        </div>

        <div class="hidden overflow-x-auto sm:block">
          <table class="data-table [&_th]:whitespace-nowrap">
            <thead>
              <tr>
                <th>Poste</th>
                <th>État</th>
                <th>Version</th>
                <th>Dernier signal</th>
                <th>Dernière synchro</th>
                <th class="num">Rejets</th>
                @if (canForget() || canRename()) {
                  <th><span class="sr-only">Actions</span></th>
                }
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.terminal.terminalId) {
                <tr [attr.data-state]="row.terminal.state">
                  <td>
                    @if (editing() === row.terminal.terminalId) {
                      <form
                        class="flex flex-wrap items-center gap-2"
                        (ngSubmit)="save(row.terminal)"
                      >
                        <input
                          class="input min-h-8 w-40"
                          name="name"
                          maxlength="60"
                          placeholder="Nom : Caisse 1"
                          aria-label="Nom du poste"
                          [(ngModel)]="draftName"
                          data-testid="terminal-name-input"
                        />
                        <input
                          class="input min-h-8 w-44"
                          name="location"
                          maxlength="80"
                          placeholder="Emplacement (facultatif)"
                          aria-label="Emplacement du poste"
                          [(ngModel)]="draftLocation"
                        />
                        <button type="submit" class="btn btn-primary btn-sm" [disabled]="busy()">
                          Enregistrer
                        </button>
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm"
                          (click)="editing.set(null)"
                        >
                          Annuler
                        </button>
                      </form>
                      <p class="mt-1 text-xs text-subtle">
                        Identifiant unique : <code>{{ row.terminal.terminalId }}</code> (préfixe des
                        tickets)
                      </p>
                    } @else {
                      <span class="flex items-center gap-2 font-medium">
                        <app-icon name="monitor" [size]="14" class="text-subtle" />
                        <span class="min-w-0">
                          @if (row.terminal.name) {
                            <span class="block">{{ row.terminal.name }}</span>
                            <span class="block text-xs font-normal text-subtle">
                              <code>{{ row.terminal.terminalId }}</code>
                              @if (row.terminal.location) {
                                · {{ row.terminal.location }}
                              }
                            </span>
                          } @else {
                            <code class="text-[0.8125rem]">{{ row.terminal.terminalId }}</code>
                          }
                        </span>
                        @if (row.terminal.terminalId === current()) {
                          <span class="badge badge-brand">ce poste</span>
                        }
                      </span>
                    }
                  </td>
                  <td>
                    <span [class]="badgeClass(row.badge.tone)">
                      <app-icon [name]="row.badge.icon" [size]="12" />{{ row.badge.label }}
                    </span>
                  </td>
                  <td class="text-muted">{{ row.terminal.appVersion }}</td>
                  <td
                    class="whitespace-nowrap"
                    [attr.title]="row.terminal.lastSeenAt | date: 'dd/MM/yyyy HH:mm:ss'"
                  >
                    {{ ago(row.terminal.lastSeenAt) }}
                  </td>
                  <td
                    class="whitespace-nowrap text-muted"
                    [attr.title]="row.terminal.lastSyncAt | date: 'dd/MM/yyyy HH:mm:ss'"
                  >
                    {{ ago(row.terminal.lastSyncAt) }}
                  </td>
                  <td class="num">
                    @if (row.terminal.rejectedSales === null) {
                      <span class="text-subtle" title="Non transmis par cette version">—</span>
                    } @else if (row.terminal.rejectedSales > 0) {
                      <span class="badge badge-danger">{{ row.terminal.rejectedSales }}</span>
                    } @else {
                      <span class="text-subtle">0</span>
                    }
                  </td>
                  @if (canForget() || canRename()) {
                    <td class="whitespace-nowrap text-right">
                      @if (canRename() && editing() !== row.terminal.terminalId) {
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm"
                          [disabled]="busy()"
                          (click)="edit(row.terminal)"
                          [attr.aria-label]="'Nommer le poste ' + row.terminal.terminalId"
                          [attr.data-testid]="'rename-' + row.terminal.terminalId"
                        >
                          <app-icon name="pencil" [size]="14" />
                          {{ row.terminal.name ? 'Renommer' : 'Nommer' }}
                        </button>
                      }
                      @if (canForget() && row.terminal.state === 'STALE') {
                        <button
                          type="button"
                          class="btn btn-ghost btn-sm"
                          [disabled]="busy()"
                          (click)="forget.emit(row.terminal)"
                          [attr.aria-label]="'Oublier le poste ' + row.terminal.terminalId"
                        >
                          <app-icon name="trash" [size]="14" />
                          Oublier
                        </button>
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>

        <ul class="divide-y divide-[var(--border)] sm:hidden" aria-label="Postes de caisse">
          @for (row of rows(); track row.terminal.terminalId) {
            <li class="space-y-2 px-4 py-3" [attr.data-state]="row.terminal.state">
              <div class="flex items-center justify-between gap-2">
                <span class="flex min-w-0 items-center gap-2">
                  @if (row.terminal.name) {
                    <span class="min-w-0">
                      <span class="block truncate text-sm font-semibold">{{
                        row.terminal.name
                      }}</span>
                      <code class="block truncate text-xs text-subtle">{{
                        row.terminal.terminalId
                      }}</code>
                    </span>
                  } @else {
                    <code class="min-w-0 truncate text-sm font-semibold">{{
                      row.terminal.terminalId
                    }}</code>
                  }
                  @if (row.terminal.terminalId === current()) {
                    <span class="badge badge-brand">ce poste</span>
                  }
                </span>
                <span [class]="badgeClass(row.badge.tone)">
                  <app-icon [name]="row.badge.icon" [size]="12" />{{ row.badge.label }}
                </span>
              </div>
              <dl class="grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
                <dt class="text-muted">Dernier signal</dt>
                <dd class="text-right">{{ ago(row.terminal.lastSeenAt) }}</dd>
                <dt class="text-muted">Dernière synchro</dt>
                <dd class="text-right">{{ ago(row.terminal.lastSyncAt) }}</dd>
                <dt class="text-muted">Version</dt>
                <dd class="truncate text-right">{{ row.terminal.appVersion }}</dd>
                @if (row.terminal.rejectedSales) {
                  <dt class="text-muted">Rejets</dt>
                  <dd class="text-right font-semibold text-danger">
                    {{ row.terminal.rejectedSales }}
                  </dd>
                }
              </dl>
              @if (canRename()) {
                @if (editing() === row.terminal.terminalId) {
                  <form class="grid gap-2" (ngSubmit)="save(row.terminal)">
                    <input
                      class="input"
                      name="name"
                      maxlength="60"
                      placeholder="Nom : Caisse 1"
                      aria-label="Nom du poste"
                      [(ngModel)]="draftName"
                    />
                    <input
                      class="input"
                      name="location"
                      maxlength="80"
                      placeholder="Emplacement (facultatif)"
                      aria-label="Emplacement du poste"
                      [(ngModel)]="draftLocation"
                    />
                    <span class="flex gap-2">
                      <button type="submit" class="btn btn-primary btn-sm" [disabled]="busy()">
                        Enregistrer
                      </button>
                      <button
                        type="button"
                        class="btn btn-ghost btn-sm"
                        (click)="editing.set(null)"
                      >
                        Annuler
                      </button>
                    </span>
                  </form>
                } @else {
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm -ml-2"
                    [disabled]="busy()"
                    (click)="edit(row.terminal)"
                  >
                    <app-icon name="pencil" [size]="14" />
                    {{ row.terminal.name ? 'Renommer ce poste' : 'Nommer ce poste' }}
                  </button>
                }
              }
              @if (canForget() && row.terminal.state === 'STALE') {
                <button
                  type="button"
                  class="btn btn-ghost btn-sm -ml-2"
                  [disabled]="busy()"
                  (click)="forget.emit(row.terminal)"
                >
                  <app-icon name="trash" [size]="14" />
                  Oublier ce poste
                </button>
              }
            </li>
          }
        </ul>
      } @else {
        <div class="empty-state">
          <app-icon name="monitor" [size]="22" />
          <p class="font-medium text-ink">Aucun poste n'a encore donné signe de vie</p>
          <p>Un poste apparaît ici dès que l'application de caisse y est ouverte.</p>
        </div>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .segmented {
      flex-wrap: nowrap;
      white-space: nowrap;
    }
    .count {
      margin-left: 0.25rem;
      color: var(--text-subtle);
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class TerminalBoardView {
  readonly board = input.required<TerminalBoard>();
  /** Current time in ms, ticked by the page so relative times stay fresh. */
  readonly now = input.required<number>();
  /** Identifier of the register displaying the board, flagged "ce poste". */
  readonly current = input<string | null>(null);
  readonly canForget = input(false);
  readonly busy = input(false);
  readonly canRename = input(false);
  readonly forget = output<TerminalView>();
  readonly forgetStale = output<void>();
  /** A blank name takes the name away (the register shows its id alone). */
  readonly rename = output<{ terminalId: string; name: string; location: string }>();

  /** Register whose name is being edited. */
  protected readonly editing = signal<string | null>(null);
  protected draftName = '';
  protected draftLocation = '';

  protected readonly states = SYNC_STATES;
  protected readonly plural = plural;
  protected readonly badgeClass = badgeClass;
  protected readonly filter = signal<Filter>('ALL');
  /** A filter whose state no register is in any more (after a refresh) falls back to all. */
  protected readonly activeFilter = computed<Filter>(() => {
    const filter = this.filter();
    return filter !== 'ALL' && this.countOf(filter) === 0 ? 'ALL' : filter;
  });

  protected readonly rows = computed<{ terminal: TerminalView; badge: SyncBadge }[]>(() => {
    const board = this.board();
    const filter = this.activeFilter();
    return board.terminals
      .filter((t) => filter === 'ALL' || t.state === filter)
      .map((terminal) => ({ terminal, badge: terminalBadge(terminal, board.observedAt) }));
  });
  protected readonly forgettableCount = computed(() => forgettable(this.board()).length);
  protected readonly staleMinutes = computed(() => Math.round(this.board().staleAfterSeconds / 60));
  protected readonly observed = computed(() => observedLabel(this.board(), this.now()));
  protected readonly observedAgo = computed(() =>
    relativeTime(this.board().observedAt, this.now()),
  );

  protected countOf(state: TerminalSyncState): number {
    const counts = this.board().counts;
    switch (state) {
      case 'SYNCED':
        return counts.synced;
      case 'PENDING':
        return counts.pending;
      case 'STALE':
        return counts.stale;
      case 'NEVER':
        return counts.never;
    }
  }

  protected edit(terminal: TerminalView): void {
    this.draftName = terminal.name ?? '';
    this.draftLocation = terminal.location ?? '';
    this.editing.set(terminal.terminalId);
  }

  protected save(terminal: TerminalView): void {
    this.rename.emit({
      terminalId: terminal.terminalId,
      name: this.draftName.trim(),
      location: this.draftLocation.trim(),
    });
    this.editing.set(null);
  }

  protected ago(at: string | null): string {
    return relativeTime(at, this.now());
  }
}
