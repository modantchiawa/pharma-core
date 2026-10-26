import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PaperFormat, PrinterMode, PrinterSettings } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { TerminalService } from '../../core/offline/terminal.service';
import { PrintSettingsService } from '../../core/printing/print-settings.service';
import { Icon } from '../../shared/ui/icon';

interface Form {
  mode: PrinterMode;
  paper: PaperFormat;
  address: string;
  widthChars: 32 | 42 | 48;
  copies: number;
  cut: boolean;
  drawerKick: boolean;
  autoPrint: boolean;
  invoicePaper: 'A4' | 'NONE';
}

/** Printer of each register: browser dialog or thermal ESC/POS printer on the network or shared. */
@Component({
  selector: 'app-printers-page',
  imports: [FormsModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page animate-in">
      <header class="page-header">
        <div>
          <h1 class="page-title">Imprimantes</h1>
          <p class="page-subtitle">
            Imprimante des tickets et des factures pour chaque poste de caisse.
          </p>
        </div>
      </header>

      <div class="grid items-start gap-6 lg:grid-cols-[20rem_minmax(0,1fr)]">
        <section class="card overflow-hidden">
          <div class="card-header">
            <h2 class="card-title">Postes</h2>
            <span class="badge">{{ terminals().length }}</span>
          </div>
          <ul class="max-h-72 divide-y lg:max-h-[34rem] divide-[var(--border)] overflow-y-auto">
            @for (t of terminals(); track t.terminalId) {
              <li>
                <button
                  type="button"
                  class="terminal"
                  [class.terminal-active]="selected()?.terminalId === t.terminalId"
                  (click)="select(t)"
                >
                  <span class="terminal-icon"><app-icon name="monitor" [size]="18" /></span>
                  <span class="min-w-0 flex-1">
                    <span class="flex items-center gap-2">
                      <strong class="truncate text-sm">{{ t.terminalId }}</strong>
                      @if (t.terminalId === terminal.terminalId) {
                        <span class="badge badge-brand">ce poste</span>
                      }
                    </span>
                    <small class="block text-xs text-muted"
                      >{{ modeLabel(t.mode) }} · {{ paperLabel(t.paper) }}</small
                    >
                  </span>
                  @if (!t.configured) {
                    <span class="badge badge-warning" title="Réglages par défaut">à régler</span>
                  }
                </button>
              </li>
            } @empty {
              <li>
                <div class="empty-state">
                  <app-icon name="monitor" [size]="22" />
                  <p>Aucun poste connu</p>
                </div>
              </li>
            }
          </ul>
          <form class="flex gap-2 border-t border-line bg-surface-2 p-3" (ngSubmit)="addTerminal()">
            <input
              class="input min-w-0 flex-1"
              placeholder="Identifiant d'un poste"
              aria-label="Identifiant d'un poste"
              [(ngModel)]="newTerminal"
              name="newTerminal"
              maxlength="40"
              pattern="[A-Za-z0-9._-]+"
            />
            <button type="submit" class="btn btn-secondary">
              <app-icon name="plus" [size]="16" />
              Ajouter
            </button>
          </form>
        </section>

        @if (selected(); as current) {
          <div class="min-w-0 space-y-4">
            <form class="card overflow-hidden" (ngSubmit)="save(current)">
              <div class="card-header">
                <h2 class="card-title flex items-center gap-2">
                  <app-icon name="printer" [size]="16" class="text-muted" />
                  Imprimante du poste {{ current.terminalId }}
                </h2>
                @if (current.configured) {
                  <span class="badge badge-success"><span class="dot"></span>Configurée</span>
                } @else {
                  <span class="badge badge-warning"><span class="dot"></span>Non configurée</span>
                }
              </div>

              <div class="divide-y divide-[var(--border)]">
                <div class="setting">
                  <div>
                    <p class="setting-title">Mode d'impression</p>
                    <p class="setting-text">Comment le ticket parvient à l'imprimante.</p>
                  </div>
                  <div
                    class="grid gap-2 sm:grid-cols-3"
                    role="radiogroup"
                    aria-label="Mode d'impression"
                  >
                    <label class="choice">
                      <input type="radio" name="mode" value="BROWSER" [(ngModel)]="form.mode" />
                      <app-icon name="printer" [size]="18" class="choice-icon" />
                      <span class="choice-title">Navigateur</span>
                      <span class="choice-text">Boîte de dialogue, toute imprimante</span>
                    </label>
                    <label class="choice">
                      <input
                        type="radio"
                        name="mode"
                        value="ESCPOS_NETWORK"
                        [(ngModel)]="form.mode"
                      />
                      <app-icon name="wifi" [size]="18" class="choice-icon" />
                      <span class="choice-title">Thermique réseau</span>
                      <span class="choice-text">ESC/POS, port 9100</span>
                    </label>
                    <label class="choice">
                      <input
                        type="radio"
                        name="mode"
                        value="ESCPOS_SHARED"
                        [(ngModel)]="form.mode"
                      />
                      <app-icon name="network" [size]="18" class="choice-icon" />
                      <span class="choice-title">Thermique partagée</span>
                      <span class="choice-text">USB partagée ou port local</span>
                    </label>
                  </div>
                </div>

                <div class="setting">
                  <div>
                    <p class="setting-title">Papier des tickets</p>
                    <p class="setting-text">Largeur du rouleau ou feuille A4.</p>
                  </div>
                  <div
                    class="grid gap-2 sm:grid-cols-3"
                    role="radiogroup"
                    aria-label="Papier des tickets"
                  >
                    <label class="choice">
                      <input
                        type="radio"
                        name="paper"
                        value="MM58"
                        [(ngModel)]="form.paper"
                        (ngModelChange)="paperChanged()"
                      />
                      <span class="choice-title">58 mm</span>
                      <span class="choice-text">Petite imprimante, style TPE</span>
                    </label>
                    <label class="choice">
                      <input
                        type="radio"
                        name="paper"
                        value="MM80"
                        [(ngModel)]="form.paper"
                        (ngModelChange)="paperChanged()"
                      />
                      <span class="choice-title">80 mm</span>
                      <span class="choice-text">Imprimante thermique standard</span>
                    </label>
                    @if (form.mode === 'BROWSER') {
                      <label class="choice">
                        <input
                          type="radio"
                          name="paper"
                          value="A4"
                          [(ngModel)]="form.paper"
                          (ngModelChange)="paperChanged()"
                        />
                        <span class="choice-title">A4</span>
                        <span class="choice-text">Ticket sur feuille</span>
                      </label>
                    }
                  </div>
                </div>

                @if (form.mode !== 'BROWSER') {
                  <div class="setting">
                    <div>
                      <p class="setting-title">Connexion</p>
                      <p class="setting-text">Adresse de l'imprimante et largeur de ligne.</p>
                    </div>
                    <div class="grid gap-4 sm:grid-cols-[minmax(0,1fr)_14rem]">
                      <label class="block"
                        ><span class="label">Adresse de l'imprimante</span>
                        <input
                          class="input font-mono"
                          [(ngModel)]="form.address"
                          name="address"
                          required
                          maxlength="255"
                          [placeholder]="
                            form.mode === 'ESCPOS_NETWORK'
                              ? '192.168.1.50:9100'
                              : '\\\\PC-CAISSE\\Ticket'
                          "
                        />
                      </label>
                      <label class="block"
                        ><span class="label">Largeur en caractères</span>
                        <select class="select" [(ngModel)]="form.widthChars" name="width">
                          <option [ngValue]="32">32 (58 mm)</option>
                          <option [ngValue]="42">42 (80 mm, police large)</option>
                          <option [ngValue]="48">48 (80 mm)</option>
                        </select>
                      </label>
                    </div>
                  </div>
                  <label class="setting setting-switch">
                    <div>
                      <p class="setting-title">Couper le papier</p>
                      <p class="setting-text">Coupe automatique à la fin de chaque ticket.</p>
                    </div>
                    <input
                      type="checkbox"
                      role="switch"
                      class="toggle"
                      [(ngModel)]="form.cut"
                      name="cut"
                    />
                  </label>
                  <label class="setting setting-switch">
                    <div>
                      <p class="setting-title">Ouvrir le tiroir-caisse</p>
                      <p class="setting-text">Impulsion envoyée au tiroir relié à l'imprimante.</p>
                    </div>
                    <input
                      type="checkbox"
                      role="switch"
                      class="toggle"
                      [(ngModel)]="form.drawerKick"
                      name="drawer"
                    />
                  </label>
                }

                <label class="setting setting-switch">
                  <div>
                    <p class="setting-title">Imprimer le ticket automatiquement</p>
                    <p class="setting-text">Après chaque vente, sans action du vendeur.</p>
                  </div>
                  <input
                    type="checkbox"
                    role="switch"
                    class="toggle"
                    [(ngModel)]="form.autoPrint"
                    name="auto"
                  />
                </label>

                <label class="setting setting-switch">
                  <div>
                    <p class="setting-title">Exemplaires</p>
                    <p class="setting-text">Nombre de tickets imprimés à chaque vente.</p>
                  </div>
                  <select class="select w-24" [(ngModel)]="form.copies" name="copies">
                    <option [ngValue]="1">1</option>
                    <option [ngValue]="2">2</option>
                    <option [ngValue]="3">3</option>
                  </select>
                </label>

                <label class="setting">
                  <div>
                    <p class="setting-title">Factures A4</p>
                    <p class="setting-text">
                      Impression des factures pour les assureurs et clients.
                    </p>
                  </div>
                  <select
                    class="select max-w-md self-center"
                    [(ngModel)]="form.invoicePaper"
                    name="invoice"
                  >
                    <option value="A4">Proposées (imprimante A4 par le navigateur)</option>
                    <option value="NONE">Masquées sur ce poste</option>
                  </select>
                </label>
              </div>

              <div class="card-footer">
                @if (status(); as text) {
                  <p class="flex min-w-0 items-center gap-2 text-sm text-muted" role="status">
                    <app-icon name="info" [size]="16" />{{ text }}
                  </p>
                }
                <div class="ml-auto flex flex-wrap gap-2">
                  <button
                    type="button"
                    class="btn btn-secondary"
                    [disabled]="busy() || form.mode === 'BROWSER' || !current.configured"
                    (click)="test(current)"
                  >
                    <app-icon name="receipt" [size]="16" />
                    Imprimer un test
                  </button>
                  <button type="submit" class="btn btn-primary" [disabled]="busy()">
                    <app-icon name="check" [size]="16" />
                    Enregistrer
                  </button>
                </div>
              </div>
            </form>

            <aside class="card card-body text-sm">
              <p class="mb-3 flex items-center gap-2 font-semibold">
                <app-icon name="help" [size]="16" class="text-muted" />
                Aide
              </p>
              <ul class="grid gap-4 text-muted md:grid-cols-3">
                <li class="flex gap-3">
                  <app-icon name="network" [size]="16" class="mt-0.5 self-start text-subtle" />
                  <p>
                    Imprimante USB sous Windows : partagez l'imprimante (Paramètres › Imprimantes ›
                    Partager) puis saisissez <code>\\\\NOM-PC\\NomPartage</code>, où NOM-PC est le
                    nom du serveur de l'officine ou du poste qui porte l'imprimante.
                  </p>
                </li>
                <li class="flex gap-3">
                  <app-icon name="wifi" [size]="16" class="mt-0.5 self-start text-subtle" />
                  <p>
                    Imprimante réseau (Ethernet ou Wi-Fi) : saisissez son adresse IP suivie du port
                    9100, par exemple <code>192.168.1.50:9100</code>. Donnez-lui une adresse fixe
                    sur la box.
                  </p>
                </li>
                <li class="flex gap-3">
                  <app-icon name="printer" [size]="16" class="mt-0.5 self-start text-subtle" />
                  <p>
                    Mode navigateur : le ticket s'imprime par la boîte de dialogue du système, utile
                    pour les tablettes et les imprimantes A4.
                  </p>
                </li>
              </ul>
            </aside>
          </div>
        } @else {
          <section class="card">
            <div class="empty-state">
              <app-icon name="printer" [size]="22" />
              <p>Choisissez un poste pour régler son imprimante.</p>
            </div>
          </section>
        }
      </div>
    </div>
  `,
  styles: `
    .terminal {
      display: flex;
      width: 100%;
      align-items: center;
      gap: 0.75rem;
      padding: 0.6rem 1rem;
      text-align: left;
      transition: background-color 120ms ease;
    }
    .terminal:hover {
      background: var(--surface-2);
    }
    .terminal-active,
    .terminal-active:hover {
      background: var(--brand-softer);
      box-shadow: inset 3px 0 0 var(--brand-primary);
    }
    .terminal-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.25rem;
      height: 2.25rem;
      flex-shrink: 0;
      border-radius: 0.6rem;
      background: var(--surface-3);
      color: var(--text-muted);
    }
    .terminal-active .terminal-icon {
      background: var(--brand-soft);
      color: var(--brand-text);
    }
    .setting {
      display: grid;
      gap: 0.75rem;
      padding: calc(1.1rem * var(--density)) calc(1.25rem * var(--density));
    }
    @media (min-width: 900px) {
      .setting {
        grid-template-columns: 14rem minmax(0, 1fr);
        gap: 1.5rem;
      }
    }
    .setting-switch {
      display: flex;
      align-items: center;
      justify-content: space-between;
      cursor: pointer;
    }
    .setting-title {
      font-size: 0.875rem;
      font-weight: 600;
    }
    .setting-text {
      margin-top: 0.15rem;
      font-size: 0.8rem;
      color: var(--text-muted);
    }
    .choice {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      padding: 0.75rem 0.9rem;
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
      cursor: pointer;
      transition:
        border-color 120ms ease,
        background-color 120ms ease,
        box-shadow 120ms ease;
    }
    .choice:hover {
      background: var(--surface-2);
    }
    .choice:has(input:checked) {
      border-color: var(--brand-primary);
      background: var(--brand-softer);
      box-shadow: 0 0 0 1px var(--brand-primary);
    }
    .choice:has(input:focus-visible) {
      outline: 2px solid var(--brand-ring);
      outline-offset: 2px;
    }
    .choice input {
      position: absolute;
      opacity: 0;
      pointer-events: none;
    }
    .choice-icon {
      align-self: flex-start;
      margin-bottom: 0.35rem;
      color: var(--text-subtle);
    }
    .choice:has(input:checked) .choice-icon {
      color: var(--brand-text);
    }
    .choice-title {
      font-size: 0.875rem;
      font-weight: 600;
    }
    .choice-text {
      font-size: 0.75rem;
      color: var(--text-muted);
    }
    .card-footer {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.75rem;
      padding: 0.75rem calc(1.25rem * var(--density));
      border-top: 1px solid var(--border);
      background: var(--surface-2);
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
export class PrintersPage {
  protected readonly terminal = inject(TerminalService);
  private readonly http = inject(HttpClient);
  private readonly printing = inject(PrintSettingsService);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/print/terminals`;
  protected readonly terminals = signal<PrinterSettings[]>([]);
  protected readonly selected = signal<PrinterSettings | null>(null);
  protected readonly status = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected newTerminal = '';
  protected form: Form = blankForm();

  constructor() {
    void this.load();
  }

  protected modeLabel(mode: PrinterMode): string {
    return mode === 'BROWSER' ? 'navigateur' : mode === 'ESCPOS_NETWORK' ? 'réseau' : 'partagée';
  }

  protected paperLabel(paper: PaperFormat): string {
    return paper === 'MM58' ? '58 mm' : paper === 'MM80' ? '80 mm' : 'A4';
  }

  protected select(settings: PrinterSettings): void {
    this.selected.set(settings);
    this.status.set(null);
    this.form = {
      mode: settings.mode,
      paper: settings.paper,
      address: settings.address ?? '',
      widthChars: settings.widthChars,
      copies: settings.copies,
      cut: settings.cut,
      drawerKick: settings.drawerKick,
      autoPrint: settings.autoPrint,
      invoicePaper: settings.invoicePaper,
    };
  }

  protected paperChanged(): void {
    this.form.widthChars = this.form.paper === 'MM58' ? 32 : 48;
  }

  protected addTerminal(): void {
    const id = this.newTerminal.trim();
    if (!/^[A-Za-z0-9._-]{1,40}$/.test(id)) {
      this.status.set('Identifiant de poste invalide');
      return;
    }
    const existing = this.terminals().find((t) => t.terminalId === id);
    const entry: PrinterSettings = existing ?? {
      terminalId: id,
      mode: 'BROWSER',
      paper: 'MM80',
      address: null,
      widthChars: 48,
      copies: 1,
      cut: true,
      drawerKick: false,
      autoPrint: true,
      invoicePaper: 'A4',
      configured: false,
    };
    if (!existing) {
      this.terminals.update((list) => [...list, entry]);
    }
    this.newTerminal = '';
    this.select(entry);
  }

  protected async save(current: PrinterSettings): Promise<void> {
    this.busy.set(true);
    this.status.set(null);
    try {
      const saved = await firstValueFrom(
        this.http.put<PrinterSettings>(`${this.api}/${encodeURIComponent(current.terminalId)}`, {
          ...this.form,
          address: this.form.mode === 'BROWSER' ? null : this.form.address.trim(),
          widthChars: this.form.paper === 'MM58' ? 32 : this.form.widthChars,
        }),
      );
      this.terminals.update((list) =>
        list.map((t) => (t.terminalId === saved.terminalId ? saved : t)),
      );
      this.select(saved);
      this.status.set('Réglages enregistrés');
      if (saved.terminalId === this.terminal.terminalId) {
        await this.printing.refresh();
      }
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ?? 'Enregistrement refusé',
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected async test(current: PrinterSettings): Promise<void> {
    this.busy.set(true);
    try {
      await firstValueFrom(
        this.http.post(`${this.api}/${encodeURIComponent(current.terminalId)}/test`, null),
      );
      this.status.set("Page de test envoyée à l'imprimante");
    } catch (error) {
      this.status.set(
        (error as { error?: { detail?: string } }).error?.detail ??
          "Impossible de joindre l'imprimante",
      );
    } finally {
      this.busy.set(false);
    }
  }

  private async load(): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<PrinterSettings[]>(this.api));
      this.terminals.set(list);
      const mine = list.find((t) => t.terminalId === this.terminal.terminalId) ?? list[0];
      if (mine) {
        this.select(mine);
      } else {
        this.newTerminal = this.terminal.terminalId;
        this.addTerminal();
      }
    } catch {
      this.status.set('Liste des postes indisponible');
    }
  }
}

function blankForm(): Form {
  return {
    mode: 'BROWSER',
    paper: 'MM80',
    address: '',
    widthChars: 48,
    copies: 1,
    cut: true,
    drawerKick: false,
    autoPrint: true,
    invoicePaper: 'A4',
  };
}
