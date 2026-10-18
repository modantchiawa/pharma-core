import { Injectable, inject } from '@angular/core';
import { PosDatabaseService } from './pos-database.service';

const TERMINAL_KEY = 'pharmacore.terminalId';

/** Stable identity of this cash register and its local ticket numbering. */
@Injectable({ providedIn: 'root' })
export class TerminalService {
  private readonly databases = inject(PosDatabaseService);

  readonly terminalId: string = this.loadOrCreateTerminalId();

  /**
   * Next ticket number, incremented inside the caller's Dexie transaction so that the number
   * and the journal entry are committed together.
   */
  async nextTicketNumber(): Promise<string> {
    const meta = this.databases.db().meta;
    const current = ((await meta.get('ticketSequence'))?.value as number | undefined) ?? 0;
    const next = current + 1;
    await meta.put({ key: 'ticketSequence', value: next });
    return `${this.terminalId}-${String(next).padStart(6, '0')}`;
  }

  private loadOrCreateTerminalId(): string {
    const existing = localStorage.getItem(TERMINAL_KEY);
    if (existing) {
      return existing;
    }
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    const random = crypto.getRandomValues(new Uint8Array(6));
    const id = 'T' + Array.from(random, (b) => alphabet[b % alphabet.length]).join('');
    localStorage.setItem(TERMINAL_KEY, id);
    return id;
  }
}
