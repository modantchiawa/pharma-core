import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import { SessionEventKind } from '../api/models';
import { AuthService } from '../auth/auth.service';
import { APP_ENVIRONMENT } from '../config';
import { TerminalService } from '../offline/terminal.service';
import { MAX_UNLOCK_FAILURES, idleMinutes, idleState, pingDue } from './idle';
import { SessionPolicyService } from './session-policy.service';
import { SessionSignals } from './session-signals';
import {
  SESSION_KEYS,
  readLastActivity,
  readLock,
  writeLastActivity,
  writeLock,
} from './session-store';

/** Activity is recorded at most once a second, shared with the other tabs every 5 seconds. */
const LOCAL_THROTTLE_MS = 1_000;
const SHARED_THROTTLE_MS = 5_000;
const TICK_MS = 1_000;
/** A journal entry must not hold the logout up when the server is slow or gone. */
const AUDIT_TIMEOUT_MS = 1_500;
const CHANNEL = 'pharmacore.session';
const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart', 'scroll'];

type SessionMessage =
  | { type: 'activity'; at: number }
  | { type: 'lock'; since: number; manual: boolean }
  | { type: 'unlock'; at: number }
  | { type: 'logout'; reason: LogoutReason; minutes: number };

export type LogoutReason = 'idle' | 'pin' | 'manual';

/**
 * Locks the screen, then closes the session, of an unattended terminal. Activity is any pointer,
 * key (a barcode scanner types keys), touch, wheel or scroll event; tabs of the same terminal
 * share it through a BroadcastChannel (and the storage event where it is missing), so working in
 * one tab keeps the others open, and a lock or logout applies to all of them. A sale being
 * committed or a page being printed holds the timers: they never interrupt it.
 *
 * The server holds the real lock: this service tells it at once when the terminal locks, reports
 * the user's activity at most once a minute when no request does, and follows its verdict (a 423
 * locks the tab, a 401 `session-expired` closes it). Locked, the tab forgets its access token and
 * the shell removes the page.
 */
@Injectable({ providedIn: 'root' })
export class IdleService {
  private readonly auth = inject(AuthService);
  private readonly policies = inject(SessionPolicyService);
  private readonly terminal = inject(TerminalService);
  private readonly signals = inject(SessionSignals);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly apiUrl = inject(APP_ENVIRONMENT).apiUrl;

  private readonly now = signal(Date.now());
  private readonly lastActivity = signal(readLastActivity() ?? Date.now());
  private readonly lockState = signal(readLock());
  private readonly failureCount = signal(0);
  private holds = 0;
  private lastLocal = 0;
  private lastShared = 0;
  private lastPing = 0;
  private channel: BroadcastChannel | null = null;
  private stopListening: (() => void) | null = null;
  private closing = false;

  readonly policy = this.policies.policy;
  readonly locked = computed(() => this.lockState() !== null);
  readonly lockedSince = computed(() => this.lockState()?.since ?? null);
  readonly lastActivityAt = this.lastActivity.asReadonly();
  readonly state = computed(() =>
    idleState(this.now(), this.lastActivity(), this.policy(), this.locked()),
  );
  readonly attemptsLeft = computed(() => Math.max(0, MAX_UNLOCK_FAILURES - this.failureCount()));
  readonly clock = this.now.asReadonly();

  /** Starts watching once a pharmacy user is in (the shell); idempotent. */
  start(destroyRef: DestroyRef): void {
    if (this.stopListening) {
      return;
    }
    this.lastActivity.set(readLastActivity() ?? Date.now());
    this.lockState.set(readLock());
    if (this.locked()) {
      this.auth.dropAccess();
    }
    const onActivity = () => this.activity();
    const options: AddEventListenerOptions = { passive: true, capture: true };
    ACTIVITY_EVENTS.forEach((type) => window.addEventListener(type, onActivity, options));
    const onBeforePrint = () => this.holds++;
    const onAfterPrint = () => this.release();
    window.addEventListener('beforeprint', onBeforePrint);
    window.addEventListener('afterprint', onAfterPrint);
    const onStorage = (event: StorageEvent) => this.onStorage(event);
    window.addEventListener('storage', onStorage);
    const onVisible = () => this.tick();
    document.addEventListener('visibilitychange', onVisible);
    if (typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel(CHANNEL);
      this.channel.onmessage = (event: MessageEvent<SessionMessage>) => this.receive(event.data);
    }
    const lockedByServer = this.signals.lockedByServer.subscribe(() => this.serverLocked());
    const closedByServer = this.signals.closedByServer.subscribe(
      () => void this.closedByServer('idle'),
    );
    const timer = setInterval(() => this.tick(), TICK_MS);
    this.stopListening = () => {
      ACTIVITY_EVENTS.forEach((type) => window.removeEventListener(type, onActivity, options));
      window.removeEventListener('beforeprint', onBeforePrint);
      window.removeEventListener('afterprint', onAfterPrint);
      window.removeEventListener('storage', onStorage);
      document.removeEventListener('visibilitychange', onVisible);
      lockedByServer.unsubscribe();
      closedByServer.unsubscribe();
      clearInterval(timer);
      this.channel?.close();
      this.channel = null;
      this.stopListening = null;
    };
    destroyRef.onDestroy(() => this.stopListening?.());
    this.tick();
  }

  /** The user is here: "Je suis là" on the warning, or any recorded activity. */
  stillHere(): void {
    this.touch(Date.now(), true);
  }

  /** Locks at once (shell button, Ctrl+L / Cmd+L). */
  lockNow(): void {
    if (!this.locked()) {
      this.lock(true);
    }
  }

  /**
   * Keeps the terminal open (a sale being committed) until the returned function is called;
   * the inactivity count restarts then.
   */
  hold(): () => void {
    this.holds++;
    let released = false;
    return () => {
      if (!released) {
        released = true;
        this.release();
      }
    };
  }

  /** The lock screen verified the user (or switched to another one). */
  unlocked(): void {
    const at = Date.now();
    this.failureCount.set(0);
    this.lockState.set(null);
    writeLock(null);
    this.touch(at, true);
    this.post({ type: 'unlock', at });
  }

  /** A wrong PIN or password on the lock screen; the fifth one closes the session. */
  async unlockFailed(): Promise<void> {
    this.failureCount.update((n) => n + 1);
    if (this.failureCount() >= MAX_UNLOCK_FAILURES) {
      await this.logout('pin');
    }
  }

  /** The server counts the wrong codes of the session's owner: its count is shown. */
  attemptsReported(attemptsLeft: number): void {
    this.failureCount.set(
      MAX_UNLOCK_FAILURES - Math.max(0, Math.min(MAX_UNLOCK_FAILURES, attemptsLeft)),
    );
  }

  /**
   * The server closed the session (logout delay, fifth wrong code): back to the sign-in screen in
   * every tab, without journaling what the server already has.
   */
  async closedByServer(reason: 'idle' | 'pin'): Promise<void> {
    if (this.closing) {
      return;
    }
    this.closing = true;
    this.post({ type: 'logout', reason, minutes: 0 });
    await this.closeHere(reason, 0);
  }

  private activity(): void {
    if (this.locked()) {
      return;
    }
    const at = Date.now();
    if (at - this.lastLocal >= LOCAL_THROTTLE_MS) {
      this.touch(at, false);
      this.ping(at);
    }
  }

  /** Activity without API calls (typing, scrolling) reaches the server at least once a minute. */
  private ping(at: number): void {
    if (this.closing || !pingDue(at, this.lastPing, this.signals.lastActivityRequest)) {
      return;
    }
    this.lastPing = at;
    firstValueFrom(this.http.post(`${this.apiUrl}/v1/me/session/activity`, {})).catch(
      () => undefined,
    );
  }

  private touch(at: number, share: boolean): void {
    this.lastLocal = at;
    this.lastActivity.set(at);
    this.now.set(at);
    if (share || at - this.lastShared >= SHARED_THROTTLE_MS) {
      this.lastShared = at;
      writeLastActivity(at);
      this.post({ type: 'activity', at });
    }
  }

  private release(): void {
    this.holds = Math.max(0, this.holds - 1);
    if (this.holds === 0 && !this.locked()) {
      this.touch(Date.now(), true);
    }
  }

  private tick(): void {
    const at = Date.now();
    if (this.holds > 0 && !this.locked()) {
      this.touch(at, false);
    }
    this.now.set(at);
    const state = this.state();
    if (state.logoutDue) {
      void this.logout('idle');
    } else if (state.phase === 'locked' && !this.locked()) {
      this.lock(false);
    }
  }

  private lock(manual: boolean): void {
    const lock = { since: Date.now(), manual };
    this.lockState.set(lock);
    this.failureCount.set(0);
    writeLock(lock);
    this.post({ type: 'lock', ...lock });
    void this.lockOnServer(manual);
  }

  /** Tells the server, then forgets the access token (even when the server cannot be reached). */
  private async lockOnServer(manual: boolean): Promise<void> {
    try {
      await firstValueFrom(
        this.http
          .post(`${this.apiUrl}/v1/me/session/lock`, {
            reason: manual ? 'MANUAL' : 'IDLE',
            terminalId: this.terminal.terminalId,
            idleMinutes: idleMinutes(Date.now(), this.lastActivity()),
          })
          .pipe(timeout(AUDIT_TIMEOUT_MS)),
      );
    } catch {
      // Offline: locked here; the server locks by itself at its own deadline
    } finally {
      if (this.locked()) {
        this.auth.dropAccess();
      }
    }
  }

  /** The server locked the session first (its deadline, another tab, the clock of this one). */
  private serverLocked(): void {
    if (!this.locked()) {
      const lock = { since: Date.now(), manual: false };
      this.lockState.set(lock);
      this.failureCount.set(0);
      writeLock(lock);
      this.post({ type: 'lock', ...lock });
    }
    this.auth.dropAccess();
  }

  /** "Se déconnecter" on the lock screen: every tab of the terminal goes back to the sign-in. */
  signOut(): Promise<void> {
    return this.logout('manual');
  }

  /** Revokes and forgets the tokens, then back to the sign-in screen with the reason. */
  private async logout(reason: LogoutReason): Promise<void> {
    if (this.closing) {
      return;
    }
    this.closing = true;
    const minutes = reason === 'idle' ? this.policy().logoutAfterMinutes : 0;
    if (reason !== 'manual') {
      await this.journal(reason === 'idle' ? 'IDLE_LOGOUT' : 'PIN_FAILURES_LOGOUT');
    }
    this.post({ type: 'logout', reason, minutes });
    await this.closeHere(reason, minutes);
  }

  private async closeHere(reason: LogoutReason, minutes: number): Promise<void> {
    this.stopListening?.();
    this.lockState.set(null);
    this.failureCount.set(0);
    await this.auth.logout();
    await this.router.navigate(['/login'], {
      queryParams: reason === 'manual' ? {} : { session: reason, minutes: minutes || null },
    });
    this.closing = false;
  }

  private receive(message: SessionMessage): void {
    switch (message.type) {
      case 'activity':
        if (!this.locked() && message.at > this.lastActivity()) {
          this.lastActivity.set(message.at);
        }
        break;
      case 'lock':
        this.lockState.set({ since: message.since, manual: message.manual });
        this.auth.dropAccess();
        break;
      case 'unlock':
        this.lockState.set(null);
        this.failureCount.set(0);
        this.lastActivity.set(message.at);
        // This tab's token was issued while locked: the next request refreshes a full one
        this.auth.dropAccess();
        break;
      case 'logout':
        if (!this.closing) {
          this.closing = true;
          void this.closeHere(message.reason, message.minutes);
        }
        break;
    }
  }

  /** Fallback of the BroadcastChannel: the shared keys of the other tabs. */
  private onStorage(event: StorageEvent): void {
    if (event.key === SESSION_KEYS.lastActivity) {
      const at = Number(event.newValue);
      if (Number.isFinite(at) && !this.locked() && at > this.lastActivity()) {
        this.lastActivity.set(at);
      }
    } else if (event.key === SESSION_KEYS.lock) {
      this.lockState.set(readLock());
      this.auth.dropAccess();
    }
  }

  private post(message: SessionMessage): void {
    this.channel?.postMessage(message);
  }

  private async journal(event: SessionEventKind): Promise<void> {
    try {
      await firstValueFrom(
        this.http
          .post(`${this.apiUrl}/v1/me/session-events`, {
            event,
            terminalId: this.terminal.terminalId,
            idleMinutes: idleMinutes(Date.now(), this.lastActivity()),
          })
          .pipe(timeout(AUDIT_TIMEOUT_MS)),
      );
    } catch {
      // Offline: the logout happens anyway, only its journal entry is lost
    }
  }
}
