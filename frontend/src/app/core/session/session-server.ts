import { HttpClient } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import { SessionEventKind } from '../api/models';

/** A lock or a journal entry must not hold the terminal up when the server is slow or gone. */
const REPORT_TIMEOUT_MS = 1_500;

/**
 * What the idle service tells the server about the session, loaded on first use: it is not
 * needed to draw the first screen.
 */
export class SessionServer {
  constructor(
    private readonly http: HttpClient,
    private readonly apiUrl: string,
  ) {}

  /** The terminal locked itself (padlock, Ctrl+L, its idle timer): the server locks the session at once. */
  async lock(manual: boolean, terminalId: string, idleMinutes: number): Promise<void> {
    await firstValueFrom(
      this.http
        .post(`${this.apiUrl}/v1/me/session/lock`, {
          reason: manual ? 'MANUAL' : 'IDLE',
          terminalId,
          idleMinutes,
        })
        .pipe(timeout(REPORT_TIMEOUT_MS)),
    );
  }

  /** The user works without calling the API: the server counts it (refused while locked). */
  activity(): Promise<unknown> {
    return firstValueFrom(this.http.post(`${this.apiUrl}/v1/me/session/activity`, {}));
  }

  /** A logout decided by the terminal, journaled under the signed-in user. */
  async journal(event: SessionEventKind, terminalId: string, idleMinutes: number): Promise<void> {
    await firstValueFrom(
      this.http
        .post(`${this.apiUrl}/v1/me/session-events`, { event, terminalId, idleMinutes })
        .pipe(timeout(REPORT_TIMEOUT_MS)),
    );
  }
}
