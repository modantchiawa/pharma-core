import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Me } from '../api/models';
import { APP_ENVIRONMENT } from '../config';

const PROFILE_KEY = 'pharmacore.profile';

/**
 * Permissions of the signed-in user, resolved server side from the pharmacy's own role matrix.
 * The last known profile is kept so that the POS keeps enforcing discount ceilings offline.
 */
@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly http = inject(HttpClient);
  private readonly env = inject(APP_ENVIRONMENT);
  private readonly profileState = signal<Me | null>(readCachedProfile());

  readonly profile = this.profileState.asReadonly();
  readonly permissions = computed(() => new Set(this.profileState()?.permissions ?? []));
  readonly maxDiscountPercent = computed(() => this.profileState()?.maxDiscountPercent ?? 0);

  has(permission: string): boolean {
    return this.permissions().has(permission);
  }

  hasAny(...permissions: string[]): boolean {
    return permissions.some((permission) => this.has(permission));
  }

  async load(): Promise<Me | null> {
    try {
      const me = await firstValueFrom(this.http.get<Me>(`${this.env.apiUrl}/v1/me`));
      this.profileState.set(me);
      localStorage.setItem(PROFILE_KEY, JSON.stringify(me));
      return me;
    } catch {
      return this.profileState();
    }
  }

  clear(): void {
    localStorage.removeItem(PROFILE_KEY);
    this.profileState.set(null);
  }
}

function readCachedProfile(): Me | null {
  try {
    const raw = localStorage.getItem(PROFILE_KEY);
    return raw ? (JSON.parse(raw) as Me) : null;
  } catch {
    return null;
  }
}
