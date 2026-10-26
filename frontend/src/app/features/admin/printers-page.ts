import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PaperFormat, PrinterMode, PrinterSettings } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { TerminalService } from '../../core/offline/terminal.service';
import { PrintSettingsService } from '../../core/printing/print-settings.service';

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
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-4 p-4 lg:grid-cols-[320px_1fr]">
      <section class="space-y-2">
        <h2 class="text-lg font-semibold">Postes</h2>
        <ul class="divide-y rounded-lg border bg-white">
          @for (t of terminals(); track t.terminalId) {
            <li>
              <button
                type="button"
                class="flex w-full items-center justify-between px-3 py-2 text-left hover:bg-slate-50"
                [class.bg-brand-dark]="selected()?.terminalId === t.terminalId"
                (click)="select(t)"
              >
                <span>
                  <strong>{{ t.terminalId }}</strong>
                  @if (t.terminalId === terminal.terminalId) {
                    <small class="ml-1 text-slate-500">(ce poste)</small>
                  }
                </span>
                <small class="text-slate-500"
                  >{{ modeLabel(t.mode) }} · {{ paperLabel(t.paper) }}</small
                >
              </button>
            </li>
          } @empty {
            <li class="px-3 py-4 text-sm text-slate-500">Aucun poste connu</li>
          }
        </ul>
        <form class="flex gap-2" (ngSubmit)="addTerminal()">
          <input
            class="field flex-1"
            placeholder="Identifiant d'un poste"
            [(ngModel)]="newTerminal"
            name="newTerminal"
            maxlength="40"
            pattern="[A-Za-z0-9._-]+"
          />
          <button type="submit" class="rounded border px-3">Ajouter</button>
        </form>
      </section>

      @if (selected(); as current) {
        <form class="space-y-3 rounded-lg border bg-white p-4" (ngSubmit)="save(current)">
          <h2 class="text-lg font-semibold">Imprimante du poste {{ current.terminalId }}</h2>
          <label class="block text-sm"
            >Mode d'impression
            <select class="field" [(ngModel)]="form.mode" name="mode">
              <option value="BROWSER">Navigateur (boîte de dialogue, toute imprimante)</option>
              <option value="ESCPOS_NETWORK">
                Imprimante thermique réseau (ESC/POS, port 9100)
              </option>
              <option value="ESCPOS_SHARED">Imprimante thermique USB partagée ou port local</option>
            </select>
          </label>
          <label class="block text-sm"
            >Papier des tickets
            <select
              class="field"
              [(ngModel)]="form.paper"
              name="paper"
              (ngModelChange)="paperChanged()"
            >
              <option value="MM58">58 mm (petite imprimante, style TPE)</option>
              <option value="MM80">80 mm (imprimante thermique standard)</option>
              @if (form.mode === 'BROWSER') {
                <option value="A4">A4 (ticket sur feuille)</option>
              }
            </select>
          </label>
          @if (form.mode !== 'BROWSER') {
            <label class="block text-sm"
              >Adresse de l'imprimante
              <input
                class="field"
                [(ngModel)]="form.address"
                name="address"
                required
                maxlength="255"
                [placeholder]="
                  form.mode === 'ESCPOS_NETWORK' ? '192.168.1.50:9100' : '\\\\PC-CAISSE\\Ticket'
                "
              />
            </label>
            <label class="block text-sm"
              >Largeur en caractères
              <select class="field" [(ngModel)]="form.widthChars" name="width">
                <option [ngValue]="32">32 (58 mm)</option>
                <option [ngValue]="42">42 (80 mm, police large)</option>
                <option [ngValue]="48">48 (80 mm)</option>
              </select>
            </label>
            <div class="flex flex-wrap gap-4 text-sm">
              <label class="flex items-center gap-2"
                ><input type="checkbox" [(ngModel)]="form.cut" name="cut" />Couper le papier</label
              >
              <label class="flex items-center gap-2"
                ><input type="checkbox" [(ngModel)]="form.drawerKick" name="drawer" />Ouvrir le
                tiroir-caisse</label
              >
            </div>
          }
          <label class="block text-sm"
            >Exemplaires
            <select class="field" [(ngModel)]="form.copies" name="copies">
              <option [ngValue]="1">1</option>
              <option [ngValue]="2">2</option>
              <option [ngValue]="3">3</option>
            </select>
          </label>
          <label class="flex items-center gap-2 text-sm"
            ><input type="checkbox" [(ngModel)]="form.autoPrint" name="auto" />Imprimer le ticket
            automatiquement après chaque vente</label
          >
          <label class="block text-sm"
            >Factures A4
            <select class="field" [(ngModel)]="form.invoicePaper" name="invoice">
              <option value="A4">Proposées (imprimante A4 par le navigateur)</option>
              <option value="NONE">Masquées sur ce poste</option>
            </select>
          </label>
          <div class="flex flex-wrap gap-2">
            <button
              type="submit"
              class="rounded bg-brand px-3 py-2 text-on-brand"
              [disabled]="busy()"
            >
              Enregistrer
            </button>
            <button
              type="button"
              class="rounded border px-3 py-2"
              [disabled]="busy() || form.mode === 'BROWSER' || !current.configured"
              (click)="test(current)"
            >
              Imprimer un test
            </button>
          </div>
          @if (status(); as text) {
            <p class="text-sm" role="status">{{ text }}</p>
          }
          <div class="rounded bg-slate-50 p-3 text-xs text-slate-700">
            <p class="font-semibold">Aide</p>
            <p>
              Imprimante USB sous Windows : partagez l'imprimante (Paramètres › Imprimantes ›
              Partager) puis saisissez <code>\\\\NOM-PC\\NomPartage</code>, où NOM-PC est le nom du
              serveur de l'officine ou du poste qui porte l'imprimante.
            </p>
            <p>
              Imprimante réseau (Ethernet ou Wi-Fi) : saisissez son adresse IP suivie du port 9100,
              par exemple <code>192.168.1.50:9100</code>. Donnez-lui une adresse fixe sur la box.
            </p>
            <p>
              Mode navigateur : le ticket s'imprime par la boîte de dialogue du système, utile pour
              les tablettes et les imprimantes A4.
            </p>
          </div>
        </form>
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
