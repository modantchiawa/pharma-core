import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { LicenseStatus } from '../api/models';
import { PermissionService } from '../auth/permission.service';
import { APP_ENVIRONMENT } from '../config';
import { CachedLicense, effectiveState } from './license';

const CACHE_PREFIX = 'pharmacore.license.';
const POLL_MS = 5 * 60_000;
const TICK_MS = 60_000;

/**
 * Subscription state of the pharmacy on this terminal: polled from the local server (hub or
 * cloud), cached per pharmacy so a terminal that loses its server keeps warning and blocking on
 * the dates it was given. The server's refusal (423) stays the real guard.
 */
@Injectable({ providedIn: 'root' })
export class LicenseStateService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly permissions = inject(PermissionService);
  private readonly cached = signal<CachedLicense | null>(null);
  private readonly now = signal(Date.now());
  private started = false;

  readonly status = computed(() => this.cached()?.status ?? null);
  readonly state = computed(() => effectiveState(this.cached(), this.now()));
  readonly blocked = computed(() => this.state()?.status === 'BLOCKED');
  /** Something to tell the pharmacy (warning, grace or block). */
  readonly notice = computed(() => {
    const state = this.state();
    return state && state.status !== 'ACTIVE' ? state : null;
  });

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    this.cached.set(this.readCache());
    void this.refresh();
    setInterval(() => void this.refresh(), POLL_MS);
    setInterval(() => this.now.set(Date.now()), TICK_MS);
  }

  /** Latest state from the server; offline, the cached one stays in force. */
  async refresh(): Promise<void> {
    try {
      this.accept(
        await firstValueFrom(
          this.http.get<LicenseStatus>(`${this.env.apiUrl}/v1/subscription/state`),
        ),
      );
    } catch {
      // Unreachable server: keep enforcing the cached state.
    }
  }

  /** Asks the server to fetch a fresh license from the cloud now (hub) and returns the new state. */
  async renew(): Promise<LicenseStatus> {
    const status = await firstValueFrom(
      this.http.post<LicenseStatus>(`${this.env.apiUrl}/v1/subscription/refresh`, {}),
    );
    this.accept(status);
    return status;
  }

  accept(status: LicenseStatus): void {
    const entry: CachedLicense = { status, fetchedAt: Date.now() };
    this.now.set(entry.fetchedAt);
    this.cached.set(entry);
    const key = this.cacheKey();
    if (key) {
      try {
        localStorage.setItem(key, JSON.stringify(entry));
      } catch {
        // Storage full or unavailable: the state lasts for the session.
      }
    }
  }

  private readCache(): CachedLicense | null {
    const key = this.cacheKey();
    if (!key) {
      return null;
    }
    try {
      const raw = localStorage.getItem(key);
      return raw ? (JSON.parse(raw) as CachedLicense) : null;
    } catch {
      return null;
    }
  }

  private cacheKey(): string | null {
    const tenant = this.permissions.profile()?.tenantId;
    return tenant ? CACHE_PREFIX + tenant : null;
  }
}
