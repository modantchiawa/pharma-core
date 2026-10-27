import { Injectable, signal } from '@angular/core';
import { SessionPolicy } from '../api/models';
import { sanitizePolicy } from './idle';
import { readPolicy, writePolicy } from './session-store';

/**
 * Inactivity delays of the pharmacy, received with every token and kept on the terminal so
 * that an offline restart still locks and closes the session on time.
 */
@Injectable({ providedIn: 'root' })
export class SessionPolicyService {
  private readonly state = signal<SessionPolicy>(readPolicy());

  readonly policy = this.state.asReadonly();

  set(policy: SessionPolicy | null | undefined): void {
    if (!policy) {
      return;
    }
    const valid = sanitizePolicy(policy);
    writePolicy(valid);
    this.state.set(valid);
  }
}
