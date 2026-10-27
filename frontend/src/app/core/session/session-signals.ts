import { Injectable } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/**
 * What the server says about the session, relayed from the HTTP layer to the idle service
 * without a dependency between them: it locked the session before this tab did (deadline,
 * another tab, clock), or closed it.
 */
@Injectable({ providedIn: 'root' })
export class SessionSignals {
  private readonly locked = new Subject<void>();
  private readonly closed = new Subject<void>();

  /** A 423 `session-locked`, or tokens refreshed while locked. */
  readonly lockedByServer: Observable<void> = this.locked.asObservable();
  /** A 401 `session-expired`: the session is over on the server. */
  readonly closedByServer: Observable<void> = this.closed.asObservable();
  /** Last request the server counts as activity (epoch ms): no ping is needed before a minute. */
  lastActivityRequest = 0;

  serverLocked(): void {
    this.locked.next();
  }

  serverClosed(): void {
    this.closed.next();
  }
}
