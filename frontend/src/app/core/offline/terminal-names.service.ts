import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { TerminalName } from '../api/models';
import { APP_ENVIRONMENT } from '../config';
import { TerminalService } from './terminal.service';

const CACHE_KEY = 'pharmacore.terminalNames';

/**
 * Names the pharmacy gave its registers ("Caisse 1", "Comptoir ordonnances") on top of their
 * unique ids. Kept on the station so names still show without the server.
 */
@Injectable({ providedIn: 'root' })
export class TerminalNamesService {
  private readonly http = inject(HttpClient);
  private readonly api = `${inject(APP_ENVIRONMENT).apiUrl}/v1/pos/terminals`;
  private readonly terminal = inject(TerminalService);

  private readonly byId = signal<ReadonlyMap<string, TerminalName>>(readCache());
  /** This station: its id and, when given, its name and place. */
  readonly self = computed<TerminalName>(
    () =>
      this.byId().get(this.terminal.terminalId) ?? {
        terminalId: this.terminal.terminalId,
        name: null,
        location: null,
      },
  );

  nameOf(terminalId: string | null | undefined): string | null {
    return terminalId ? (this.byId().get(terminalId)?.name ?? null) : null;
  }

  /** "Caisse 1 (T7K2MX9)" when named, the id alone otherwise. */
  label(terminalId: string | null | undefined, withId = true): string {
    if (!terminalId) {
      return '—';
    }
    const name = this.nameOf(terminalId);
    if (!name) {
      return terminalId;
    }
    return withId ? `${name} (${terminalId})` : name;
  }

  async refresh(): Promise<void> {
    try {
      const names = await firstValueFrom(this.http.get<TerminalName[]>(`${this.api}/names`));
      this.byId.set(new Map(names.map((n) => [n.terminalId, n])));
      writeCache(names);
    } catch {
      // offline: the names kept on the station stay
    }
  }

  async rename(terminalId: string, name: string, location: string): Promise<TerminalName> {
    const saved = await firstValueFrom(
      this.http.put<TerminalName>(`${this.api}/${encodeURIComponent(terminalId)}/name`, {
        name,
        location,
      }),
    );
    await this.refresh();
    return saved;
  }
}

function readCache(): ReadonlyMap<string, TerminalName> {
  try {
    const names = JSON.parse(localStorage.getItem(CACHE_KEY) ?? '[]') as TerminalName[];
    return new Map(names.map((n) => [n.terminalId, n]));
  } catch {
    return new Map();
  }
}

function writeCache(names: TerminalName[]): void {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(names));
  } catch {
    // storage full or blocked: names are fetched again next time
  }
}
