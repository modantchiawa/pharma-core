import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { PaperFormat, PrintResult, PrinterSettings, TerminalTicketRequest } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { TerminalService } from '../offline/terminal.service';

const CACHE_KEY = 'pharmacore.printer';
const PAGE_STYLE_ID = 'pharmacore-print-page';
const PAGE_SIZE: Record<PaperFormat, string> = {
  MM58: '58mm auto',
  MM80: '80mm auto',
  A4: 'A4',
};

/**
 * How this register prints. The settings live on the server (shared by every pharmacist who
 * configures the register) and are cached locally so an offline register keeps printing the
 * same way. Browser mode drives the operating system's print dialog with the right page size;
 * ESC/POS modes hand the job to the local server, which talks to the thermal printer.
 */
@Injectable({ providedIn: 'root' })
export class PrintSettingsService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly terminal = inject(TerminalService);
  private readonly document = inject(DOCUMENT);
  private readonly state = signal<PrinterSettings>(
    readCache() ?? defaults(this.terminal.terminalId),
  );

  readonly settings = this.state.asReadonly();

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    try {
      const settings = await firstValueFrom(
        this.http.get<PrinterSettings>(
          `${this.env.apiUrl}/v1/print/terminals/${encodeURIComponent(this.terminal.terminalId)}`,
        ),
      );
      this.state.set(settings);
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify(settings));
      } catch {
        // storage unavailable: the in-memory copy is enough for this session
      }
    } catch {
      // server unreachable: keep the cached settings
    }
  }

  /** Browser printing of what is currently rendered in the print area, at the given page size. */
  printPage(format: PaperFormat): void {
    const win = this.document.defaultView;
    if (!win) {
      return;
    }
    let style = this.document.getElementById(PAGE_STYLE_ID) as HTMLStyleElement | null;
    if (!style) {
      style = this.document.createElement('style');
      style.id = PAGE_STYLE_ID;
      this.document.head.appendChild(style);
    }
    style.textContent = `@media print { @page { size: ${PAGE_SIZE[format]}; margin: ${format === 'A4' ? '12mm' : '0'}; } }`;
    setTimeout(() => win.print());
  }

  /**
   * Prints a sale already known to the server on this register's thermal printer. Returns false
   * when the register prints through the browser or when the printer could not be reached, so
   * the caller falls back to the HTML ticket.
   */
  async printSale(saleId: string, copies?: number): Promise<boolean> {
    if (this.state().mode === 'BROWSER') {
      return false;
    }
    try {
      const params: Record<string, string> = { terminalId: this.terminal.terminalId };
      if (copies) {
        params['copies'] = String(copies);
      }
      await firstValueFrom(
        this.http.post<PrintResult>(`${this.env.apiUrl}/v1/print/receipts/${saleId}`, null, {
          params,
        }),
      );
      return true;
    } catch {
      return false;
    }
  }

  /** Prints a basket the register just committed locally, before it is synchronized. */
  async printTicket(ticket: TerminalTicketRequest): Promise<boolean> {
    if (this.state().mode === 'BROWSER') {
      return false;
    }
    try {
      await firstValueFrom(
        this.http.post<PrintResult>(`${this.env.apiUrl}/v1/print/tickets`, ticket, {
          params: { terminalId: this.terminal.terminalId },
        }),
      );
      return true;
    } catch {
      return false;
    }
  }
}

function defaults(terminalId: string): PrinterSettings {
  return {
    terminalId,
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
}

function readCache(): PrinterSettings | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    return raw ? (JSON.parse(raw) as PrinterSettings) : null;
  } catch {
    return null;
  }
}
